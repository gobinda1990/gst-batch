package gov.com.ai.webapp.service;

import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import gov.com.ai.webapp.repository.RiskRepository;
import gov.com.ai.webapp.util.PeriodUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Scores every GST 3B summary row of a period in keyset-paged chunks.
 *
 * - each page is a short query (no long-lived cursor, no ORA-01555, one connection at a time)
 * - each chunk is saved in ONE transaction instead of one commit per row
 * - row-level data problems (mapping / model) are skipped and counted, but the batch FAILS
 *   if too many rows fail, so it can no longer report SUCCESS after silently scoring nothing
 * - database failures are never swallowed as "bad rows"
 */
@Service
@Slf4j
public class RiskScoringServiceImpl implements RiskScoringService {

	private static final int MAX_LOGGED_ROW_ERRORS = 20;
	private static final int MIN_ROWS_BEFORE_FAIL_FAST = 200;

	// Oracle 11g: no FETCH FIRST / OFFSET, so ROWNUM over an ordered inline view
	private static final String COLUMNS = """
			SELECT GSTIN, RET_PERIOD,
			       NVL(TAXABLE_VALUE,0), NVL(TOTAL_OUTPUT_TAX,0), NVL(ELIGIBLE_ITC,0), NVL(UTILIZED_ITC,0),
			       NVL(REVERSED_ITC,0), NVL(INELIGIBLE_ITC,0), NVL(EXCESS_ITC,0), NVL(RCM_TOTAL_TAX,0),
			       NVL(CASH_TAX_PAID,0), NVL(ITC_PAYMENT_TOTAL,0), NVL(NIL_SUPPLY_RATIO,0)
			  FROM GST_RET_3B_SUMMARY
			 WHERE RET_PERIOD = ?
			""";

	// the first page has no "GSTIN > ?" - in Oracle '' is NULL, so an empty start key would return nothing
	private static final String FIRST_PAGE_SQL = "SELECT * FROM (" + COLUMNS + " ORDER BY GSTIN) WHERE ROWNUM <= ?";
	private static final String NEXT_PAGE_SQL = "SELECT * FROM (" + COLUMNS
			+ " AND GSTIN > ? ORDER BY GSTIN) WHERE ROWNUM <= ?";

	private record RawRow(String gstin, String period, double[] v) {
	}

	private record ScoredRow(Gst3bFeatureVector feature, double score, String gstin) {
	}

	private static final class Stats {
		long read;
		long scored;
		long saved;
		long failed;
		long loggedErrors;
	}

	private final JdbcTemplate jdbcTemplate;
	private final XgboostRiskScoringService model;
	private final Gst3bFeatureMapper mapper;
	private final RiskRepository riskRepository;
	private final TransactionTemplate chunkTx;

	@Value("${gst.risk.chunk-size:5000}")
	private int chunkSize = 5000;

	@Value("${gst.risk.chunk-retries:2}")
	private int chunkRetries = 2;

	@Value("${gst.risk.max-failure-percent:1.0}")
	private double maxFailurePercent = 1.0;

	public RiskScoringServiceImpl(JdbcTemplate jdbcTemplate, XgboostRiskScoringService model, Gst3bFeatureMapper mapper,
			RiskRepository riskRepository, TransactionTemplate transactionTemplate) {

		this.jdbcTemplate = jdbcTemplate;
		this.model = model;
		this.mapper = mapper;
		this.riskRepository = riskRepository;

		// each chunk commits independently, whatever transaction the caller may have
		this.chunkTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
		this.chunkTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	public void processRisk(YearMonth period, Long batchId) {
		processRisk(period, batchId, () -> false);
	}

	@Override
	public void processRisk(YearMonth period, Long batchId, BooleanSupplier shouldStop) {

		String retPeriod = PeriodUtils.format(period);
		int pageSize = Math.max(500, chunkSize);
		long started = System.nanoTime();

		Long totalRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM GST_RET_3B_SUMMARY WHERE RET_PERIOD = ?",
				Long.class, retPeriod);
		long total = totalRows == null ? 0 : totalRows;

		if (total == 0) {
			log.warn("Risk[batch={}, period={}] no source rows in GST_RET_3B_SUMMARY - nothing to score", batchId,
					retPeriod);
			return;
		}

		log.info("Risk[batch={}, period={}] start: rows={}, pageSize={}, maxFailure={}%", batchId, retPeriod, total,
				pageSize, maxFailurePercent);

		Stats stats = new Stats();
		String lastGstin = null;
		int page = 0;

		while (true) {

			if (shouldStop.getAsBoolean() || Thread.currentThread().isInterrupted()) {
				throw new IllegalStateException(String.format(
						"Risk scoring aborted after page %d (batch lock lost or shutdown): read=%d saved=%d failed=%d",
						page, stats.read, stats.saved, stats.failed));
			}

			List<RawRow> rows = readPage(retPeriod, lastGstin, pageSize);

			if (rows.isEmpty()) {
				break;
			}

			page++;
			long pageStart = System.nanoTime();
			lastGstin = rows.get(rows.size() - 1).gstin();
			stats.read += rows.size();

			List<ScoredRow> scoredRows = scorePage(rows, stats, batchId, retPeriod);
			stats.scored += scoredRows.size();

			saveChunk(scoredRows, batchId, retPeriod, page, stats);

			logProgress(batchId, retPeriod, page, rows.size(), scoredRows.size(), stats, total, pageStart, started);

			failFastIfTooManyFailures(stats, batchId, retPeriod);
		}

		String elapsed = fmt(Duration.ofNanos(System.nanoTime() - started).toMillis());

		if (stats.read != total) {
			log.warn("Risk[batch={}, period={}] read {} rows but count was {} - source changed during the run",
					batchId, retPeriod, stats.read, total);
		}

		if (stats.failed > 0) {
			log.warn("Risk[batch={}, period={}] finished with {} failed rows out of {} ({} shown in log above)", batchId,
					retPeriod, stats.failed, stats.read, Math.min(stats.loggedErrors, MAX_LOGGED_ROW_ERRORS));
		}

		enforceFailureThreshold(stats, batchId, retPeriod, 1);

		log.info("Risk[batch={}, period={}] done: read={}, scored={}, saved={}, failed={} in {}", batchId, retPeriod,
				stats.read, stats.scored, stats.saved, stats.failed, elapsed);
	}

	// ------------------------------------------------------------------ read

	private List<RawRow> readPage(String retPeriod, String afterGstin, int limit) {

		String sql = afterGstin == null ? FIRST_PAGE_SQL : NEXT_PAGE_SQL;

		return jdbcTemplate.query(sql, ps -> {

			int i = 1;
			ps.setString(i++, retPeriod);
			if (afterGstin != null) {
				ps.setString(i++, afterGstin);
			}
			ps.setInt(i, limit);
			ps.setFetchSize(Math.min(limit, 1000));

		}, rs -> {

			List<RawRow> out = new ArrayList<>(limit);

			while (rs.next()) {
				double[] v = new double[11];
				for (int j = 0; j < v.length; j++) {
					v[j] = rs.getDouble(j + 3);
				}
				out.add(new RawRow(rs.getString(1), rs.getString(2), v));
			}

			return out;
		});
	}

	// ----------------------------------------------------------------- score

	private List<ScoredRow> scorePage(List<RawRow> rows, Stats stats, Long batchId, String retPeriod) {

		// 1) map raw columns to features (bad rows are skipped and counted)
		List<Gst3bFeatureVector> features = new ArrayList<>(rows.size());

		for (RawRow r : rows) {

			try {
				double[] v = r.v();

				features.add(mapper.map(r.gstin(), r.period(), v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8],
						v[9], v[10]));

			} catch (Exception ex) {
				rowFailed(stats, batchId, retPeriod, r.gstin(), "mapping", ex);
			}
		}

		if (features.isEmpty()) {
			return List.of();
		}

		// 2) ONE native predict call for the whole page. A throw here is systemic
		// (model not loaded / native error), so it fails the batch instead of skipping rows.
		double[] scores = model.predictBatch(features);

		// 3) NaN = this row was rejected (invalid feature or non-finite model output)
		List<ScoredRow> out = new ArrayList<>(features.size());

		for (int i = 0; i < features.size(); i++) {

			Gst3bFeatureVector f = features.get(i);

			if (Double.isNaN(scores[i])) {

				String reason = model.validationError(f);
				rowFailed(stats, batchId, retPeriod, f.gstin(), "scoring",
						new IllegalArgumentException(reason != null ? reason : "model returned a non-finite score"));
				continue;
			}

			out.add(new ScoredRow(f, scores[i], f.gstin()));
		}

		return out;
	}

	// ------------------------------------------------------------------ save

	private void saveChunk(List<ScoredRow> rows, Long batchId, String retPeriod, int page, Stats stats) {

		if (rows.isEmpty()) {
			return;
		}

		for (int attempt = 0;; attempt++) {

			try {
				chunkTx.executeWithoutResult(s -> {
					for (ScoredRow r : rows) {
						riskRepository.save(r.feature(), r.score(), batchId);
					}
				});

				stats.saved += rows.size();
				return;

			} catch (DataAccessResourceFailureException ex) {
				// database unreachable: not a bad row - fail the batch instead of skipping everything
				throw ex;

			} catch (TransientDataAccessException ex) {

				if (attempt >= chunkRetries) {
					log.error("Risk[batch={}, period={}] page {} still failing after {} attempts - saving row by row",
							batchId, retPeriod, page, attempt + 1, ex);
					break;
				}

				long waitMs = 2_000L * (attempt + 1);
				log.warn("Risk[batch={}, period={}] page {} transient save failure (attempt {}/{}): {} - retry in {} ms",
						batchId, retPeriod, page, attempt + 1, chunkRetries + 1, ex.getMessage(), waitMs);
				sleep(waitMs);

			} catch (RuntimeException ex) {
				log.warn("Risk[batch={}, period={}] page {} chunk save failed ({}), falling back to row by row",
						batchId, retPeriod, page, ex.toString());
				break;
			}
		}

		// isolate the bad row(s): one transaction per row, only for this chunk
		for (ScoredRow r : rows) {

			try {
				chunkTx.executeWithoutResult(s -> riskRepository.save(r.feature(), r.score(), batchId));
				stats.saved++;

			} catch (DataAccessResourceFailureException ex) {
				throw ex;

			} catch (RuntimeException ex) {
				rowFailed(stats, batchId, retPeriod, r.gstin(), "save", ex);
			}
		}
	}

	// --------------------------------------------------------- failure logic

	private void rowFailed(Stats stats, Long batchId, String retPeriod, String gstin, String phase, Exception ex) {

		stats.failed++;
		stats.loggedErrors++;

		if (stats.loggedErrors <= 3) {
			log.error("Risk[batch={}, period={}] {} failed for GSTIN={}", batchId, retPeriod, phase, mask(gstin), ex);

		} else if (stats.loggedErrors <= MAX_LOGGED_ROW_ERRORS) {
			log.warn("Risk[batch={}, period={}] {} failed for GSTIN={}: {}", batchId, retPeriod, phase, mask(gstin),
					ex.toString());

		} else if (stats.loggedErrors == MAX_LOGGED_ROW_ERRORS + 1) {
			log.warn("Risk[batch={}, period={}] further row failures are counted but no longer logged individually",
					batchId, retPeriod);
		}
	}

	private void failFastIfTooManyFailures(Stats stats, Long batchId, String retPeriod) {
		enforceFailureThreshold(stats, batchId, retPeriod, MIN_ROWS_BEFORE_FAIL_FAST);
	}

	private void enforceFailureThreshold(Stats stats, Long batchId, String retPeriod, long minRows) {

		if (stats.read < minRows || stats.failed == 0) {
			return;
		}

		double pct = stats.failed * 100.0 / stats.read;

		if (pct > maxFailurePercent) {
			throw new IllegalStateException(String.format(
					"Risk scoring failed: %d of %d rows failed (%.2f%% > allowed %.2f%%), period=%s batch=%d",
					stats.failed, stats.read, pct, maxFailurePercent, retPeriod, batchId));
		}
	}

	// --------------------------------------------------------------- helpers

	private void logProgress(Long batchId, String retPeriod, int page, int pageRows, int pageScored, Stats stats,
			long total, long pageStart, long runStart) {

		long elapsedMs = Duration.ofNanos(System.nanoTime() - runStart).toMillis();
		long pageMs = Duration.ofNanos(System.nanoTime() - pageStart).toMillis();
		long rate = elapsedMs == 0 ? 0 : stats.read * 1000 / elapsedMs;
		long etaMs = stats.read == 0 ? 0 : elapsedMs * Math.max(0, total - stats.read) / stats.read;

		log.info("Risk[batch={}, period={}] page {}: rows={} scored={} in {} ms | progress {}/{} ({}%) "
				+ "saved={} failed={} | {} rows/s elapsed={} ETA~{}", batchId, retPeriod, page, pageRows, pageScored,
				pageMs, stats.read, total, Math.min(100, stats.read * 100 / total), stats.saved, stats.failed, rate,
				fmt(elapsedMs), fmt(etaMs));
	}

	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting to retry risk save", ie);
		}
	}

	private static String fmt(long ms) {

		Duration d = Duration.ofMillis(ms);
		return String.format("%02d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
	}

	private String mask(String gstin) {

		if (gstin == null || gstin.length() < 7) {
			return "***";
		}

		return gstin.substring(0, 2) + "*****" + gstin.substring(gstin.length() - 4);
	}
}