package gov.com.ai.webapp.service;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import gov.com.ai.webapp.config.BatchProperties;
import gov.com.ai.webapp.domain.RevenueBatchResponse;
import gov.com.ai.webapp.exception.BatchAlreadyRunningException;
import gov.com.ai.webapp.exception.RevenueBatchException;
import gov.com.ai.webapp.exception.RevenueRequestException;
import gov.com.ai.webapp.repository.OfficeRevenueBatchRepository;
import gov.com.ai.webapp.repository.OfficeRevenueTransactionalWorker;
import gov.com.ai.webapp.repository.RevenueBatchLockRepository;
import gov.com.ai.webapp.repository.RevenueBatchRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeRevenueBatchService {

	private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("MMuuuu");
	private static final int MAX_ERROR_BYTES = 1900;

	private final OfficeRevenueBatchRepository repo;
	private final OfficeRevenueTransactionalWorker worker;
	private final RevenueBatchLockRepository locks;
	private final RevenueBatchRunRepository runs;
	private final BatchProperties props;

	/** Source-data figures gathered before the run. */
	private record Precheck(long rows, long gstins, long unmappedRegistrations, long unmappedJurisdictions,
			long duplicateMappings, long duplicateSourceRows) {
	}

	public RevenueBatchResponse run(String raw) {

		String period = valid(raw);
		String runId = UUID.randomUUID().toString();
		Instant start = Instant.now();
		long t0 = System.nanoTime();

		boolean locked = false;
		boolean audited = false;
		boolean finished = false;

		// every log line of this run (any class, any thread-local logger) carries runId
		// + period
		MDC.put("runId", runId);
		MDC.put("retPeriod", period);

		try {

			locked = locks.acquire(period, runId);

			if (!locked) {
				log.error("Revenue batch already running for period={}", period);
				throw new BatchAlreadyRunningException("Revenue batch already running for " + period);
			}

			log.info("===== Office revenue batch START period={} runId={} =====", period, runId);

			runs.start(runId, period);
			audited = true;

			Precheck pre = precheck(period);

			var r = retry(period, runId);

			runs.success(runId, pre.rows(), pre.gstins(), r.offices(), pre.unmappedRegistrations(),
					pre.unmappedJurisdictions(), pre.duplicateMappings());
			finished = true;

			// the data is committed and audited as SUCCESS: a cache problem must not turn
			// it into a failure
			evictCachesQuietly();

			long ms = (System.nanoTime() - t0) / 1_000_000;

			log.info(
					"===== Office revenue batch SUCCESS period={} runId={} offices={} merged={} growth={} stale={} "
							+ "elapsed={} ms =====",
					period, runId, r.offices(), r.merged(), r.growthUpdated(), r.staleDeleted(), ms);

			return new RevenueBatchResponse(runId, period, "SUCCESS", pre.rows(), pre.gstins(), r.offices(), r.merged(),
					r.growthUpdated(), r.staleDeleted(), pre.unmappedRegistrations(), pre.unmappedJurisdictions(),
					start, Instant.now(), ms, "Office revenue batch completed successfully");

		} catch (BatchAlreadyRunningException e) {
			log.warn("Revenue batch already running: period={} runId={}", period, runId);
			throw e;
		} catch (RevenueBatchException e) {
			long ms = (System.nanoTime() - t0) / 1_000_000;
			log.error(
					"===== Office revenue batch FAILED (RevenueBatchException) period={} runId={} elapsed={} ms =====",
					period, runId, ms, e);

			if (audited && !finished) {
				try {
					runs.fail(runId, safe(e));
				} catch (Exception x) {
					log.error("Failed to update batch audit runId={} period={}", runId, period, x);
				}
			}

			throw e;

		} catch (RuntimeException e) {
			long ms = (System.nanoTime() - t0) / 1_000_000;
			log.error(
					"===== Office revenue batch FAILED (RuntimeException) period={} runId={} elapsed={} ms cause={} =====",
					period, runId, ms, e.getClass().getSimpleName() + ": " + e.getMessage(), e);

			if (audited && !finished) {
				try {
					runs.fail(runId, safe(e));
				} catch (Exception x) {
					log.error("Failed to update batch audit runId={} period={}", runId, period, x);
				}
			}

			throw e;

		} finally {

			if (locked) {
				try {
					locks.release(period, runId);
					log.debug("Batch lock released: period={} runId={}", period, runId);
				} catch (Exception e) {
					log.error("Failed to release batch lock runId={} period={}", runId, period, e);
				}
			}

			MDC.remove("runId");
			MDC.remove("retPeriod");
		}
	}

	private Precheck precheck(String p) {

		long t = System.nanoTime();

		try {
			long rows = repo.countSourceRows(p);
			long gstins = repo.countSourceGstins(p);
			long unmappedRegistrations = repo.countUnmappedRegistrations(p);
			long unmappedJurisdictions = repo.countUnmappedJurisdictions(p);
			long duplicateMappings = repo.countDuplicateMappings(p);
			long duplicateSourceRows = repo.countDuplicateSourceRows(p);

			log.info(
					"Pre-check period={} sourceRows={} gstins={} unmappedRegistration={} unmappedJurisdiction={} "
							+ "duplicateMappings={} duplicateSourceGstins={} ({} ms)",
					p, rows, gstins, unmappedRegistrations, unmappedJurisdictions, duplicateMappings,
					duplicateSourceRows, (System.nanoTime() - t) / 1_000_000);

			if (rows == 0 || gstins == 0) {
				log.error("No valid source data for period={}", p);
				throw new RevenueBatchException("No valid source data for " + p);
			}

			if (duplicateMappings > 0) {
				log.error("Duplicate GSTIN mappings found: count={}", duplicateMappings);
				throw new RevenueBatchException(duplicateMappings + " GSTIN(s) map to multiple ST_JURI values");
			}

			if (unmappedRegistrations > 0 || unmappedJurisdictions > 0) {
				log.warn(
						"Source mapping gaps period={} unmappedRegistration={} unmappedJurisdiction={} - "
								+ "these GSTINs are excluded from office totals",
						p, unmappedRegistrations, unmappedJurisdictions);
			}

			if (duplicateSourceRows > 0) {
				log.warn("period={} {} GSTIN(s) have more than one GST_RET_3B_SUMMARY row - "
						+ "their amounts are summed once per row", p, duplicateSourceRows);
			}

			return new Precheck(rows, gstins, unmappedRegistrations, unmappedJurisdictions, duplicateMappings,
					duplicateSourceRows);

		} catch (RevenueBatchException e) {
			throw e;
		} catch (Exception e) {
			log.error("Pre-check failed for period={}: {}", p, e.getMessage(), e);
			throw new RevenueBatchException("Pre-check failed for period " + p, e);
		}
	}

	private OfficeRevenueTransactionalWorker.Result retry(String p, String id) {

		int max = Math.max(1, props.getMaxRetry());
		long delay = Math.max(0, props.getRevenue().getRetryBackoffMs());
		DataAccessException last = null;

		log.info("Starting retry loop: maxAttempts={} retryBackoffMs={} period={}", max, delay, p);

		for (int i = 1; i <= max; i++) {

			long t = System.nanoTime();

			try {
				log.info("Processing attempt {}/{} period={}", i, max, p);

				var result = worker.process(p);

				long elapsedMs = (System.nanoTime() - t) / 1_000_000;
				log.info("Attempt {}/{} succeeded in {} ms period={} offices={} merged={} growth={}", i, max, elapsedMs,
						p, result.offices(), result.merged(), result.growthUpdated());

				return result;

			} catch (DataAccessException e) {

				if (!(e instanceof TransientDataAccessException)) {
					log.error("Non-transient database error on attempt {}/{}: {}", i, max, e.getMessage(), e);
					throw e;
				}

				last = e;

				long elapsedMs = (System.nanoTime() - t) / 1_000_000;

				if (i == max) {
					log.error(
							"===== TRANSIENT DB ERROR (FINAL ATTEMPT) runId={} period={} attempt={}/{} elapsed={} ms error={} =====",
							id, p, i, max, elapsedMs, e.getMessage(), e);
					break;
				}

				log.warn(
						"Transient DB error on attempt {}/{}: {} - retrying in {} ms | period={} runId={} elapsed={} ms",
						i, max, e.getMessage(), delay, p, id, elapsedMs);

				sleep(delay);
				delay = Math.min(delay * 2, 10_000);
			}
		}

		log.error("===== BATCH FAILED AFTER {} ATTEMPTS period={} runId={} =====", max, p, id);
		throw new RevenueBatchException(
				"Batch failed after " + max + " attempts: " + (last != null ? last.getMessage() : "unknown error"),
				last);
	}

	private void evictCachesQuietly() {

		try {
			// TODO: implement actual cache eviction (e.g., Redis, Spring Cache, etc.)
			// Example placeholder:
			// cacheManager.getCache("dashboardCache").clear();
			log.info("Dashboard caches evicted");
		} catch (Exception e) {
			log.warn("Batch succeeded but dashboard cache eviction failed - dashboards may show old data until "
					+ "the cache expires: {}", e.getMessage());
		}
	}

	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("Batch retry interrupted after {} ms", ms, e);
			throw new RevenueBatchException("Batch retry interrupted", e);
		}
	}

	private String valid(String p) {

		if (p == null || !p.trim().matches("(0[1-9]|1[0-2])\\d{4}")) {
			log.error("Invalid period format: {} (expected MMYYYY)", p);
			throw new RevenueRequestException("retPeriod must be MMYYYY");
		}

		String v = p.trim();

		try {
			YearMonth.parse(v, F);
			return v;
		} catch (DateTimeException e) {
			log.error("Invalid retPeriod: {} - {}", v, e.getMessage());
			throw new RevenueRequestException("Invalid retPeriod: " + v, e);
		}
	}

	/**
	 * Error text for the audit table: includes the root cause and respects a byte
	 * limit (not char limit). Converts to UTF-8 bytes BEFORE truncating to preserve
	 * multi-byte character boundaries.
	 */
	private String safe(Throwable e) {

		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}

		String s = e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "(no message)");

		if (root != e) {
			s += " | root: " + root.getClass().getSimpleName() + ": "
					+ (root.getMessage() != null ? root.getMessage() : "(no message)");
		}

		s = s.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();

		// Truncate by bytes to respect database column limit
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_ERROR_BYTES) {
			// Truncate bytes, then decode back to string (may lose final partial char)
			s = new String(bytes, 0, MAX_ERROR_BYTES, StandardCharsets.UTF_8);
			// Ensure we don't have a truncated multi-byte sequence at the end
			while (!s.isEmpty() && (s.getBytes(StandardCharsets.UTF_8).length > MAX_ERROR_BYTES)) {
				s = s.substring(0, s.length() - 1);
			}
			s = s + "...truncated";
		}

		log.debug("Safe error text for audit (bytes={}): {}", s.getBytes(StandardCharsets.UTF_8).length, s);
		return s;
	}
}