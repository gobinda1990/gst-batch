package gov.com.ai.webapp.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import gov.com.ai.webapp.domain.DefaulterCandidate;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Repository
public class DefaulterSourceRepository {

	private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("MMuuuu");

	private static final int DEFAULT_FETCH_SIZE = 1_000;
	private static final int MAX_FETCH_SIZE = 5_000;
	private static final int PAGE_RETRIES = 2;
	
	private static final String FETCH_SQL = """
			WITH eligible_gstin AS (
			    SELECT GSTIN, ST_JURI
			      FROM (
			            SELECT UPPER(reg.GSTIN_ID)          AS GSTIN,
			                   MAX(reg.STATE_JURSD_CD)      AS ST_JURI
			              FROM %1$s reg
			             WHERE reg.GSTIN_ID IS NOT NULL
			               AND reg.AUTH_STATUS = 'A'
			               AND (? = 'Y' OR UPPER(reg.PREF) = 'M')
			               %2$s
			             GROUP BY UPPER(reg.GSTIN_ID)
			             ORDER BY UPPER(reg.GSTIN_ID)
			           )
			     WHERE ROWNUM <= ?
			),

			cur_return AS (
			    SELECT GSTIN, FILING_DATE, TAXABLE_VALUE, TOTAL_OUTPUT_TAX,
			           OUTPUT_IGST, OUTPUT_CGST, OUTPUT_SGST, OUTPUT_CESS
			      FROM (
			            SELECT s.GSTIN, s.FILING_DATE, s.TAXABLE_VALUE, s.TOTAL_OUTPUT_TAX,
			                   s.OUTPUT_IGST, s.OUTPUT_CGST, s.OUTPUT_SGST, s.OUTPUT_CESS,
			                   ROW_NUMBER() OVER (PARTITION BY s.GSTIN ORDER BY s.FILING_DATE DESC NULLS LAST) AS RN
			              FROM GST_RET_3B_SUMMARY s
			              JOIN eligible_gstin e ON e.GSTIN = s.GSTIN
			             WHERE s.RET_PERIOD = ?
			           )
			     WHERE RN = 1
			),

			last_filed AS (
			    SELECT GSTIN, RET_PERIOD, FILING_DATE, AVG_3M, AVG_6M, AVG_12M
			      FROM (
			            SELECT g.GSTIN,
			                   g.RET_PERIOD,
			                   h.FILING_DATE,
			                   g.AVG_3M_OUTPUT_TAX  AS AVG_3M,
			                   g.AVG_6M_OUTPUT_TAX  AS AVG_6M,
			                   g.AVG_12M_OUTPUT_TAX AS AVG_12M,
			                   ROW_NUMBER() OVER (PARTITION BY g.GSTIN ORDER BY g.PERIOD_DATE DESC) AS RN
			              FROM GST_3B_GROWTH_ANALYTICS g
			              JOIN eligible_gstin e ON e.GSTIN = g.GSTIN
			              JOIN GST_RET_3B_SUMMARY h
			                ON h.GSTIN = g.GSTIN
			               AND h.RET_PERIOD = g.RET_PERIOD
			               AND h.FILING_DATE IS NOT NULL
			             WHERE g.PERIOD_DATE <  ?
			               AND g.PERIOD_DATE >= ADD_MONTHS(?, -%3$d)
			           )
			     WHERE RN = 1
			),

			historical_defaults AS (
			    SELECT d.GSTIN, COUNT(*) AS DEFAULT_COUNT
			      FROM GST_3B_RETURN_DEFAULTER d
			      JOIN eligible_gstin e ON e.GSTIN = d.GSTIN
			     WHERE d.FILING_STATUS = 'NOT_FILED'
			       AND d.ACTIVE_FLAG = 'Y'
			       AND CASE
			             WHEN REGEXP_LIKE(d.RET_PERIOD, '^(0[1-9]|1[0-2])(19|20)[0-9]{2}$')
			             THEN TO_DATE('01' || d.RET_PERIOD, 'DDMMYYYY')
			           END < ?
			     GROUP BY d.GSTIN
			)

			SELECT e.GSTIN,
			       ? AS RET_PERIOD,
			       ? AS DUE_DATE,
			       CASE WHEN cur.GSTIN IS NULL THEN 'N' ELSE 'Y' END AS FILED,
			       cur.FILING_DATE,
			       NVL(cur.TAXABLE_VALUE, 0) AS TAXABLE_VALUE,
			       NVL(cur.TOTAL_OUTPUT_TAX,
			           NVL(cur.OUTPUT_IGST, 0) + NVL(cur.OUTPUT_CGST, 0)
			           + NVL(cur.OUTPUT_SGST, 0) + NVL(cur.OUTPUT_CESS, 0)) AS OUTPUT_TAX,
			       lf.RET_PERIOD  AS LAST_FILED_PERIOD,
			       lf.FILING_DATE AS LAST_FILING_DATE,
			       NVL(lf.AVG_3M, 0)  AS AVG_OUTPUT_TAX_3M,
			       NVL(lf.AVG_6M, 0)  AS AVG_OUTPUT_TAX_6M,
			       NVL(lf.AVG_12M, 0) AS AVG_OUTPUT_TAX_12M,
			       growth.GROWTH_TREND AS GROWTH_STATUS,
			       risk.RISK_LEVEL,
			       NVL(risk.RISK_SCORE, 0) AS RISK_SCORE,
			       NVL(hd.DEFAULT_COUNT, 0) AS HISTORICAL_DEFAULT_COUNT,
			       e.ST_JURI,
			       j.JURISDICTION_NAME
			  FROM eligible_gstin e
			  LEFT JOIN cur_return cur ON cur.GSTIN = e.GSTIN
			  LEFT JOIN last_filed lf ON lf.GSTIN = e.GSTIN
			  LEFT JOIN historical_defaults hd ON hd.GSTIN = e.GSTIN
			  LEFT JOIN GST_3B_GROWTH_ANALYTICS growth
			         ON growth.GSTIN = e.GSTIN
			        AND growth.RET_PERIOD = CASE WHEN cur.GSTIN IS NOT NULL THEN ? ELSE lf.RET_PERIOD END
			  LEFT JOIN GST_RET_3B_RISK_PROFILE risk
			         ON risk.GSTIN = e.GSTIN
			        AND risk.RET_PERIOD = CASE WHEN cur.GSTIN IS NOT NULL THEN ? ELSE lf.RET_PERIOD END
			  LEFT JOIN gst_master_jurisdiction j ON j.JURISDICTION_CODE = e.ST_JURI
			 ORDER BY e.GSTIN
			""";

	private static final String COUNT_SQL = """
			SELECT COUNT(DISTINCT UPPER(reg.GSTIN_ID))
			  FROM %1$s reg
			 WHERE reg.GSTIN_ID IS NOT NULL
			   AND reg.AUTH_STATUS = 'A'
			   AND (? = 'Y' OR UPPER(TRIM(eg.PREF) = 'M')
			""";

	private static final String COVERAGE_SQL = """
			SELECT (SELECT COUNT(*) FROM GST_3B_GROWTH_ANALYTICS WHERE RET_PERIOD = ?) AS GROWTH_ROWS,
			       (SELECT COUNT(*) FROM GST_RET_3B_SUMMARY      WHERE RET_PERIOD = ?) AS SUMMARY_ROWS
			  FROM DUAL
			""";

	private final JdbcTemplate jdbcTemplate;
	private final int queryTimeoutSeconds;
	private final int historyMonths;

	private final String registrationSchema;
	private final String tablePrefix;

	/** optional: force ONE table for every period. Blank = derive the table from the return period. */
	private final String fixedTable;

	private final ConcurrentMap<String, String> sqlCache = new ConcurrentHashMap<>();
	private final Set<String> verifiedTables = ConcurrentHashMap.newKeySet();

	public DefaulterSourceRepository(JdbcTemplate jdbcTemplate,
			@Value("${gst.defaulter.registration-schema:GST_REGISTRATION}") String registrationSchema,
			@Value("${gst.defaulter.registration-table-prefix:qrmp_freq}") String tablePrefix,
			@Value("${gst.defaulter.registration-table:}") String fixedTable,
			@Value("${gst.defaulter.history-months:36}") int historyMonths,
			@Value("${gst.defaulter.query-timeout-seconds:300}") int queryTimeoutSeconds) {

		// these values end up in SQL text, so they must be plain identifiers
		String ident = "[A-Za-z][A-Za-z0-9_$#]*";

		this.registrationSchema = registrationSchema == null ? "" : registrationSchema.trim();
		this.tablePrefix = tablePrefix == null ? "" : tablePrefix.trim();
		this.fixedTable = fixedTable == null || fixedTable.isBlank() ? null : fixedTable.trim();

		if (!this.registrationSchema.isEmpty() && !this.registrationSchema.matches(ident)) {
			throw new IllegalArgumentException("Invalid gst.defaulter.registration-schema: " + registrationSchema);
		}

		if (!this.tablePrefix.matches(ident)) {
			throw new IllegalArgumentException("Invalid gst.defaulter.registration-table-prefix: " + tablePrefix);
		}

		if (this.fixedTable != null && !this.fixedTable.matches(ident + "(\\." + ident + ")?")) {
			throw new IllegalArgumentException("Invalid gst.defaulter.registration-table: " + fixedTable);
		}

		this.jdbcTemplate = jdbcTemplate;
		this.historyMonths = Math.min(120, Math.max(12, historyMonths));
		this.queryTimeoutSeconds = Math.max(0, queryTimeoutSeconds);

		log.info("DefaulterSourceRepository ready: registrationTable={}, historyMonths={}, queryTimeoutSeconds={}",
				this.fixedTable != null ? this.fixedTable
						: (this.registrationSchema.isEmpty() ? "" : this.registrationSchema + ".") + this.tablePrefix
								+ "_<quarter>_<yy> (derived from return period)",
				this.historyMonths, this.queryTimeoutSeconds);
	}

	// ==================================================================
	// STREAM PROCESSING
	// ==================================================================

	/**
	 * Pushes the whole population to {@code consumer}, one page at a time, in GSTIN order. Memory stays at one page.
	 * If the consumer fails, the log names the page and the GSTIN to resume after
	 * (pass it back as {@code startAfterGstin}).
	 *
	 * @param expectedTotal from {@link #countEligibleTaxpayers}; only used for the progress % (0 = unknown)
	 * @return rows delivered
	 */
	public long forEachChunk(String retPeriod, LocalDate dueDate, String startAfterGstin, int chunkSize,
			long expectedTotal, Consumer<List<DefaulterCandidate>> consumer) {

		if (consumer == null) {
			throw new IllegalArgumentException("consumer must not be null");
		}

		final int size = normalizeFetchSize(chunkSize);
		final long started = System.nanoTime();

		String checkpoint = normalize(startAfterGstin);
		long processed = 0;
		int page = 0;

		while (true) {

			List<DefaulterCandidate> rows = fetchPage(retPeriod, dueDate, checkpoint, size, false);

			if (rows.isEmpty()) {
				break;
			}

			page++;

			String first = rows.get(0).gstin();
			String last = rows.get(rows.size() - 1).gstin();

			try {
				consumer.accept(rows);

			} catch (RuntimeException ex) {
				log.error("Defaulter source stream: consumer failed on page={} (gstin {}..{}, {} rows). "
						+ "{} rows delivered before this page; resume with startAfterGstin={}", page, first, last,
						rows.size(), processed, checkpoint);
				throw ex;
			}

			processed += rows.size();
			checkpoint = last;

			long elapsed = elapsedMillis(started);
			long rate = elapsed == 0 ? 0 : processed * 1000 / elapsed;

			if (expectedTotal > 0) {
				long etaMs = elapsed * Math.max(0, expectedTotal - processed) / processed;
				log.info("Defaulter source stream period={} page={} rows={} gstin {}..{} | delivered {}/{} ({}%) "
						+ "{} rows/s elapsed={} s ETA~{} s", retPeriod, page, rows.size(), first, last, processed,
						expectedTotal, Math.min(100, processed * 100 / expectedTotal), rate, elapsed / 1000,
						etaMs / 1000);
			} else {
				log.info("Defaulter source stream period={} page={} rows={} gstin {}..{} | delivered {} {} rows/s "
						+ "elapsed={} s", retPeriod, page, rows.size(), first, last, processed, rate, elapsed / 1000);
			}

			if (rows.size() < size) {
				break;
			}
		}

		log.info("Defaulter source stream finished period={} pages={} rows={} in {} ms", retPeriod, page, processed,
				elapsedMillis(started));

		return processed;
	}

	/**
	 * Lazy, ordered stream of candidates; pages are fetched on demand while the stream is consumed
	 * (bounded memory: one page). Sequential only. Example:
	 * {@code repo.streamCandidates(p, due, null, 2000).filter(...).forEach(...)}.
	 * A database failure surfaces as a {@link DataAccessException} from the terminal operation.
	 */
	public Stream<DefaulterCandidate> streamCandidates(String retPeriod, LocalDate dueDate, String startAfterGstin,
			int pageSize) {

		validatePeriod(retPeriod);

		if (dueDate == null) {
			throw new IllegalArgumentException("dueDate must not be null");
		}

		final int size = normalizeFetchSize(pageSize);

		Spliterator<DefaulterCandidate> spliterator = new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE,
				Spliterator.ORDERED | Spliterator.NONNULL) {

			private final ArrayDeque<DefaulterCandidate> buffer = new ArrayDeque<>();
			private String checkpoint = normalize(startAfterGstin);
			private boolean exhausted;

			@Override
			public boolean tryAdvance(Consumer<? super DefaulterCandidate> action) {

				if (buffer.isEmpty() && !exhausted) {

					List<DefaulterCandidate> rows = fetchPage(retPeriod, dueDate, checkpoint, size, true);

					if (rows.isEmpty()) {
						exhausted = true;
					} else {
						buffer.addAll(rows);
						checkpoint = rows.get(rows.size() - 1).gstin();
						exhausted = rows.size() < size;
					}
				}

				DefaulterCandidate next = buffer.poll();

				if (next == null) {
					return false;
				}

				action.accept(next);
				return true;
			}
		};

		return StreamSupport.stream(spliterator, false);
	}

	// ==================================================================
	// FETCH ONE PAGE
	// ==================================================================

	public List<DefaulterCandidate> fetchCandidates(String retPeriod, LocalDate dueDate, String lastGstin,
			int requestedSize) {

		return fetchPage(retPeriod, dueDate, lastGstin, requestedSize, true);
	}

	private List<DefaulterCandidate> fetchPage(String retPeriod, LocalDate dueDate, String lastGstin,
			int requestedSize, boolean logPage) {

		validatePeriod(retPeriod);

		if (dueDate == null) {
			throw new IllegalArgumentException("dueDate must not be null");
		}

		final int fetchSize = normalizeFetchSize(requestedSize);
		final String checkpoint = normalize(lastGstin);
		final boolean quarterEnd = isQuarterEndPeriod(retPeriod);
		final Date periodStart = Date.valueOf(YearMonth.parse(retPeriod, PERIOD_FORMAT).atDay(1));
		final long started = System.nanoTime();

		try {

			final String table = registrationTable(retPeriod);
			ensureTableExists(table, retPeriod);

			if (log.isDebugEnabled()) {
				log.debug("Fetching defaulter source page: period={}, table={}, filter={}, after={}, size={}",
						retPeriod, table, filterName(quarterEnd), checkpoint, fetchSize);
			}

			// bind order = order of the ? markers in FETCH_SQL
			List<Object> args = new ArrayList<>(12);
			args.add(quarterEnd ? "Y" : "N"); // eligible_gstin: quarter / monthly rule
			if (checkpoint != null) {
				args.add(checkpoint); // eligible_gstin: keyset  GSTIN > ?
			}
			args.add(fetchSize); // eligible_gstin: ROWNUM <= ?
			args.add(retPeriod); // cur_return: requested period
			args.add(periodStart); // last_filed: PERIOD_DATE < ?
			args.add(periodStart); // last_filed: PERIOD_DATE >= ADD_MONTHS(?, -n)
			args.add(periodStart); // historical_defaults: before requested period
			args.add(retPeriod); // select RET_PERIOD
			args.add(Date.valueOf(dueDate)); // select DUE_DATE
			args.add(retPeriod); // growth join (filers)
			args.add(retPeriod); // risk join (filers)

			List<DefaulterCandidate> result = queryWithRetry(fetchSql(table, checkpoint != null), args, retPeriod,
					checkpoint);

			if (result.isEmpty()) {
				if (logPage) {
					log.info("Defaulter source page empty: period={}, filter={}, after={}, {} ms", retPeriod,
							filterName(quarterEnd), checkpoint, elapsedMillis(started));
				}
				return Collections.emptyList();
			}

			validateOrdering(result, checkpoint);

			if (logPage) {
				long ms = elapsedMillis(started);

				log.info("Defaulter source page: period={}, filter={}, records={}, gstin {}..{}, after={}, {} ms, "
						+ "{} rows/s{}", retPeriod, filterName(quarterEnd), result.size(), result.get(0).gstin(),
						result.get(result.size() - 1).gstin(), checkpoint, ms,
						String.format(Locale.ROOT, "%.0f", ms > 0 ? result.size() * 1000.0 / ms : result.size()),
						result.size() < fetchSize ? " (last page)" : "");
			}

			return result;

		} catch (QueryTimeoutException ex) {

			log.error("Defaulter source page TIMED OUT after {} s: period={}, filter={}, after={}, size={}",
					queryTimeoutSeconds, retPeriod, filterName(quarterEnd), checkpoint, fetchSize);
			throw ex;

		} catch (RuntimeException ex) {

			// one line only: the caller logs the stack trace once
			log.error("Defaulter source page failed: period={}, filter={}, after={}, size={}, {} ms, cause={}",
					retPeriod, filterName(quarterEnd), checkpoint, fetchSize, elapsedMillis(started), ex.toString());
			throw ex;
		}
	}

	private List<DefaulterCandidate> queryWithRetry(String sql, List<Object> args, String retPeriod,
			String checkpoint) {

		for (int attempt = 0;; attempt++) {

			try {

				List<DefaulterCandidate> rows = jdbcTemplate.query(sql, ps -> bind(ps, args),
						(rs, n) -> new DefaulterCandidate(normalize(rs.getString("GSTIN")),
								trim(rs.getString("RET_PERIOD")), toLocalDate(rs.getDate("DUE_DATE")),
								"Y".equalsIgnoreCase(rs.getString("FILED")), toLocalDate(rs.getDate("FILING_DATE")),
								nz(rs.getBigDecimal("TAXABLE_VALUE")), nz(rs.getBigDecimal("OUTPUT_TAX")),
								trim(rs.getString("LAST_FILED_PERIOD")), toLocalDate(rs.getDate("LAST_FILING_DATE")),
								nz(rs.getBigDecimal("AVG_OUTPUT_TAX_3M")), nz(rs.getBigDecimal("AVG_OUTPUT_TAX_6M")),
								nz(rs.getBigDecimal("AVG_OUTPUT_TAX_12M")), trim(rs.getString("GROWTH_STATUS")),
								trim(rs.getString("RISK_LEVEL")), nz(rs.getBigDecimal("RISK_SCORE")),
								Math.max(rs.getInt("HISTORICAL_DEFAULT_COUNT"), 0), trim(rs.getString("ST_JURI")),
								trim(rs.getString("JURISDICTION_NAME"))));

				return rows == null ? Collections.emptyList() : rows;

			} catch (QueryTimeoutException ex) {
				// a query that already ran for the whole timeout will not be faster the second time
				throw ex;

			} catch (TransientDataAccessException ex) {

				// read-only query: safe to retry (deadlock / lock timeout / connection blip)
				if (attempt >= PAGE_RETRIES) {
					throw ex;
				}

				long waitMs = 1_000L * (attempt + 1);

				log.warn("Transient error on defaulter source page (attempt {}/{}), period={}, after={}: {} - "
						+ "retry in {} ms", attempt + 1, PAGE_RETRIES + 1, retPeriod, checkpoint, ex.getMessage(),
						waitMs);

				try {
					Thread.sleep(waitMs);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw ex;
				}
			}
		}
	}

	private void bind(PreparedStatement ps, List<Object> args) throws SQLException {

		if (queryTimeoutSeconds > 0) {
			ps.setQueryTimeout(queryTimeoutSeconds);
		}

		ps.setFetchSize(1_000);

		int i = 1;

		for (Object a : args) {
			if (a instanceof String s) {
				ps.setString(i++, s);
			} else if (a instanceof Integer n) {
				ps.setInt(i++, n);
			} else if (a instanceof Date d) {
				ps.setDate(i++, d);
			} else {
				throw new IllegalStateException("Unsupported bind type: " + a.getClass());
			}
		}
	}

	// ==================================================================
	// COUNT
	// ==================================================================

	public long countEligibleTaxpayers(String retPeriod) {

		validatePeriod(retPeriod);

		final boolean quarterEnd = isQuarterEndPeriod(retPeriod);
		final long started = System.nanoTime();

		try {

			String table = registrationTable(retPeriod);
			ensureTableExists(table, retPeriod);

			Long count = jdbcTemplate.queryForObject(countSql(table), Long.class, quarterEnd ? "Y" : "N");

			long total = count == null ? 0L : count;

			log.info("Eligible taxpayers: period={}, table={}, quarterEnd={}, filter={}, count={}, {} ms", retPeriod,
					table, quarterEnd, filterName(quarterEnd), total, elapsedMillis(started));

			checkGrowthCoverage(retPeriod);

			return total;

		} catch (RuntimeException ex) {

			log.error("Eligible taxpayer count failed: period={}, quarterEnd={}, {} ms, cause={}", retPeriod,
					quarterEnd, elapsedMillis(started), ex.toString());

			throw ex;
		}
	}

	/**
	 * The 3/6/12-month averages come from GST_3B_GROWTH_ANALYTICS. If growth was not computed for the previous
	 * period those averages silently become 0, so warn once per run. Never fails the run.
	 */
	private void checkGrowthCoverage(String retPeriod) {

		try {

			String previous = YearMonth.parse(retPeriod, PERIOD_FORMAT).minusMonths(1).format(PERIOD_FORMAT);

			long[] c = jdbcTemplate.queryForObject(COVERAGE_SQL,
					(rs, n) -> new long[] { rs.getLong("GROWTH_ROWS"), rs.getLong("SUMMARY_ROWS") }, previous,
					previous);

			long growthRows = c == null ? 0 : c[0];
			long summaryRows = c == null ? 0 : c[1];

			if (summaryRows > 0 && growthRows < summaryRows) {
				log.warn("Growth analytics incomplete for previous period {}: growthRows={} < summaryRows={} - "
						+ "average output tax for the missing GSTINs will be 0. Run the GST 3B growth batch first.",
						previous, growthRows, summaryRows);
			} else {
				log.info("Growth analytics coverage OK for previous period {}: growthRows={}, summaryRows={}",
						previous, growthRows, summaryRows);
			}

		} catch (RuntimeException ex) {
			log.warn("Could not verify growth analytics coverage: {}", ex.toString());
		}
	}

	// ==================================================================
	// QRMP TABLE PER RETURN PERIOD
	// ==================================================================

	/**
	 * <pre>
	 *   04,05,06 -> qrmp_freq_apr_jun_yy      07,08,09 -> qrmp_freq_jul_sep_yy
	 *   10,11,12 -> qrmp_freq_oct_dec_yy      01,02,03 -> qrmp_freq_jan_mar_yy
	 * </pre>
	 * yy = last two digits of the return period's year: 032026 -> qrmp_freq_jan_mar_26, 012027 -> ..._jan_mar_27.
	 */
	String registrationTable(String retPeriod) {

		if (fixedTable != null) {
			return fixedTable;
		}

		int month = Integer.parseInt(retPeriod.substring(0, 2));
		String yy = retPeriod.substring(4);

		String quarter = month <= 3 ? "jan_mar" : month <= 6 ? "apr_jun" : month <= 9 ? "jul_sep" : "oct_dec";

		String name = tablePrefix + "_" + quarter + "_" + yy;

		return registrationSchema.isEmpty() ? name : registrationSchema + "." + name;
	}

	/** A clear message instead of a bare ORA-00942 when a quarter's table has not been loaded yet. */
	private void ensureTableExists(String table, String retPeriod) {

		if (verifiedTables.contains(table)) {
			return;
		}

		int dot = table.indexOf('.');
		String owner = dot < 0 ? null : table.substring(0, dot);
		String name = dot < 0 ? table : table.substring(dot + 1);

		Integer found = owner == null
				? jdbcTemplate.queryForObject("""
						SELECT COUNT(*) FROM ALL_OBJECTS
						 WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
						   AND OBJECT_NAME = UPPER(?)
						   AND OBJECT_TYPE IN ('TABLE','VIEW','SYNONYM')
						""", Integer.class, name)
				: jdbcTemplate.queryForObject("""
						SELECT COUNT(*) FROM ALL_OBJECTS
						 WHERE OWNER = UPPER(?)
						   AND OBJECT_NAME = UPPER(?)
						   AND OBJECT_TYPE IN ('TABLE','VIEW','SYNONYM')
						""", Integer.class, owner, name);

		if (found == null || found == 0) {
			throw new IllegalStateException("QRMP frequency table " + table + " for return period " + retPeriod
					+ " does not exist or is not accessible - load it (or grant access) before running this period");
		}

		verifiedTables.add(table);

		log.info("Using QRMP frequency table {} for return period {}", table, retPeriod);
	}

	private String fetchSql(String table, boolean withCheckpoint) {

		return sqlCache.computeIfAbsent(table + (withCheckpoint ? "|NEXT" : "|FIRST"),
				k -> FETCH_SQL.formatted(table, withCheckpoint ? "AND UPPER(TRIM(reg.GSTIN_ID)) > ?" : "",
						historyMonths));
	}

	private String countSql(String table) {
		return sqlCache.computeIfAbsent(table + "|COUNT", k -> COUNT_SQL.formatted(table));
	}

	// ==================================================================
	// HELPERS
	// ==================================================================

	private boolean isQuarterEndPeriod(String retPeriod) {

		int month = Integer.parseInt(retPeriod.substring(0, 2));

		return month == 3 || month == 6 || month == 9 || month == 12;
	}

	private String filterName(boolean quarterEnd) {
		return quarterEnd ? "ALL_ACTIVE" : "MONTHLY_PRF_M";
	}

	/** GSTINs must be unique and strictly ascending, otherwise keyset paging would skip or repeat rows. */
	private void validateOrdering(List<DefaulterCandidate> rows, String checkpoint) {

		String previous = checkpoint;
		Set<String> seen = new HashSet<>(Math.max(16, rows.size() * 2));

		for (DefaulterCandidate row : rows) {

			if (row == null) {
				throw new IllegalStateException("Source query returned null candidate");
			}

			String current = normalize(row.gstin());

			if (current == null) {
				throw new IllegalStateException("Source query returned null/blank GSTIN");
			}

			if (!seen.add(current)) {
				throw new IllegalStateException("Duplicate GSTIN returned by source query: " + current
						+ " (duplicate rows in the growth, risk-profile or jurisdiction master tables?)");
			}

			if (previous != null && current.compareTo(previous) <= 0) {
				throw new IllegalStateException(
						"GSTIN keyset order violation. previous=" + previous + ", current=" + current);
			}

			previous = current;
		}
	}

	private void validatePeriod(String period) {

		if (period == null || !period.matches("(0[1-9]|1[0-2])\\d{4}")) {
			throw new IllegalArgumentException("Invalid RET_PERIOD=" + period + ". Expected MMYYYY.");
		}

		try {
			YearMonth.parse(period, PERIOD_FORMAT);
		} catch (DateTimeParseException ex) {
			throw new IllegalArgumentException("Invalid RET_PERIOD=" + period + ". Expected valid MMYYYY.", ex);
		}
	}

	private int normalizeFetchSize(int requestedSize) {

		if (requestedSize <= 0) {
			log.warn("Invalid requested fetch size={}; using default={}", requestedSize, DEFAULT_FETCH_SIZE);
			return DEFAULT_FETCH_SIZE;
		}

		if (requestedSize > MAX_FETCH_SIZE) {
			log.warn("Requested fetch size={} exceeds maximum={}; capping", requestedSize, MAX_FETCH_SIZE);
			return MAX_FETCH_SIZE;
		}

		return requestedSize;
	}

	private String normalize(String value) {

		String normalized = trim(value);

		return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
	}

	private String trim(String value) {

		if (value == null) {
			return null;
		}

		String trimmed = value.trim();

		return trimmed.isEmpty() ? null : trimmed;
	}

	private BigDecimal nz(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private LocalDate toLocalDate(Date value) {
		return value == null ? null : value.toLocalDate();
	}

	private long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}
}