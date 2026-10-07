package gov.com.ai.webapp.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import gov.com.ai.webapp.config.BatchProperties;
import gov.com.ai.webapp.domain.ClaimResult;
import gov.com.ai.webapp.util.PeriodUtils;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class MonthlyBatchService implements DisposableBean {

	public static final String BATCH_TYPE = "GST_3B_MONTHLY";

	private static final int MAX_ERROR_BYTES = 1900;

	private final BatchClaimService claimService;
	private final GstGrowthService growthService;
	private final RiskScoringService riskScoringService;
	private final JdbcTemplate jdbcTemplate;
	private final BatchProperties properties;

	// bounded: 2 batches at once, 5 waiting, then submit() is rejected (HTTP 503)
	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(5), namedFactory("gst3b-batch"));

	private final ScheduledExecutorService heartbeat = java.util.concurrent.Executors
			.newSingleThreadScheduledExecutor(namedFactory("gst3b-heartbeat"));

	public MonthlyBatchService(BatchClaimService claimService, GstGrowthService growthService,
			RiskScoringService riskScoringService, JdbcTemplate jdbcTemplate, BatchProperties properties) {

		this.claimService = claimService;
		this.growthService = growthService;
		this.riskScoringService = riskScoringService;
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
	}

	/**
	 * Claims the run (fast) and executes it in the background. Used by the admin
	 * endpoint so the HTTP thread is not held for hours.
	 */
	public ClaimResult submit(YearMonth period) {

		ClaimResult claim = claimService.claim(BATCH_TYPE, period);

		if (!claim.claimed()) {
			log.info("GST 3B batch not started for {}: {} (batchId={})", PeriodUtils.format(period), claim.reason(),
					claim.batchId());
			return claim;
		}

		try {
			executor.execute(() -> run(period, claim));

		} catch (RejectedExecutionException ex) {
			// release the claim immediately so it can be retried, not stuck until lock
			// expiry
			log.error("Batch queue full - rejecting batch {} for {}", claim.batchId(), PeriodUtils.format(period));
			markFailed(claim, new IllegalStateException("Batch queue full, run rejected"));
			throw ex;
		}

		return claim;
	}

	/**
	 * Synchronous variant (schedulers / CLI). Blocks until the batch finishes.
	 */
	public void process(YearMonth period) {

		ClaimResult claim = claimService.claim(BATCH_TYPE, period);

		if (!claim.claimed()) {
			log.info("GST 3B batch not started for {}: {} (batchId={})", PeriodUtils.format(period), claim.reason(),
					claim.batchId());
			return;
		}

		run(period, claim);
	}

	public List<Map<String, Object>> status(YearMonth period) {

		return jdbcTemplate.queryForList("""
				SELECT BATCH_ID, STATUS, ATTEMPT_COUNT, TOTAL_RECORDS, LOCK_OWNER,
				       STARTED_AT, COMPLETED_AT, LOCK_UNTIL, ERROR_MESSAGE
				  FROM GST_3B_BATCH_CONTROL
				 WHERE BATCH_TYPE = ?
				   AND RET_PERIOD = ?
				""", BATCH_TYPE, PeriodUtils.format(period));
	}

	// ------------------------------------------------------------------

	private void run(YearMonth period, ClaimResult claim) {

		String retPeriod = PeriodUtils.format(period);
		long startNanos = System.nanoTime();

		MDC.put("batchId", String.valueOf(claim.batchId()));
		MDC.put("retPeriod", retPeriod);

		AtomicBoolean lockLost = new AtomicBoolean(false);
		ScheduledFuture<?> heartbeatTask = startHeartbeat(claim, lockLost);

		try {

			log.info("===== GST 3B batch START period={} owner={} =====", retPeriod, claim.owner());

			long sourceCount = timed("count-source", () -> growthService.countSourceRecords(retPeriod));

			updateTotal(claim, sourceCount);

			if (sourceCount == 0) {
				log.warn("No source records found for {} - batch will complete with nothing to process", retPeriod);
			}

			timed("growth", () -> growthService.processGrowth(period, claim.batchId(), lockLost::get));

			failIfLockLost(lockLost, "growth");

			timed("risk", () -> {
				riskScoringService.processRisk(period, claim.batchId(), lockLost::get);
				return null;
			});

			failIfLockLost(lockLost, "risk");

			markSuccess(claim);

			log.info("===== GST 3B batch SUCCESS period={} records={} elapsed={} =====", retPeriod, sourceCount,
					elapsed(startNanos));

		} catch (Throwable t) {

			log.error("===== GST 3B batch FAILED period={} elapsed={} =====", retPeriod, elapsed(startNanos), t);

			markFailed(claim, t);

			if (t instanceof Error err) {
				throw err;
			}

		} finally {
			heartbeatTask.cancel(false);
			MDC.clear();
		}
	}

	private <T> T timed(String step, java.util.function.Supplier<T> action) {

		long start = System.nanoTime();
		log.info("Step [{}] started", step);

		try {
			T result = action.get();
			log.info("Step [{}] finished in {}", step, elapsed(start));
			return result;

		} catch (RuntimeException ex) {
			log.error("Step [{}] failed after {}", step, elapsed(start));
			throw ex;
		}
	}

	// ---- lock heartbeat: keeps LOCK_UNTIL ahead so a long run is not reclaimed
	// ----

	private ScheduledFuture<?> startHeartbeat(ClaimResult claim, AtomicBoolean lockLost) {

		long periodSeconds = Math.max(30L, properties.getLockMinutes() * 60L / 3L);
		AtomicInteger beats = new AtomicInteger();

		return heartbeat.scheduleWithFixedDelay(() -> {
			try {
				int updated = jdbcTemplate.update("""
						UPDATE GST_3B_BATCH_CONTROL
						   SET LOCK_UNTIL = SYSTIMESTAMP + NUMTODSINTERVAL(?, 'MINUTE'),
						       UPDATED_AT = SYSTIMESTAMP
						 WHERE BATCH_ID = ?
						   AND LOCK_OWNER = ?
						   AND STATUS = 'RUNNING'
						""", properties.getLockMinutes(), claim.batchId(), claim.owner());

				if (updated == 0) {
					lockLost.set(true);
					log.error("Batch {} lost its lock (taken over or status changed) - aborting after current step",
							claim.batchId());
				} else {
					log.debug("Batch {} heartbeat #{}", claim.batchId(), beats.incrementAndGet());
				}

			} catch (Exception ex) {
				// never let the task die - a failed beat is retried next tick
				log.warn("Batch {} heartbeat failed: {}", claim.batchId(), ex.toString());
			}
		}, periodSeconds, periodSeconds, TimeUnit.SECONDS);
	}

	private void failIfLockLost(AtomicBoolean lockLost, String afterStep) {
		if (lockLost.get()) {
			throw new IllegalStateException("Batch lock lost during step '" + afterStep + "'; another node may own it");
		}
	}

	// ---- control-table updates: all guarded by LOCK_OWNER so a stale run cannot
	// overwrite a newer one ----

	private void updateTotal(ClaimResult claim, long count) {

		jdbcTemplate.update("""
				UPDATE GST_3B_BATCH_CONTROL
				   SET TOTAL_RECORDS = ?,
				       UPDATED_AT = SYSTIMESTAMP
				 WHERE BATCH_ID = ?
				   AND LOCK_OWNER = ?
				""", count, claim.batchId(), claim.owner());
	}

	private void markSuccess(ClaimResult claim) {

		int updated = jdbcTemplate.update("""
				UPDATE GST_3B_BATCH_CONTROL
				   SET STATUS = 'SUCCESS',
				       COMPLETED_AT = SYSTIMESTAMP,
				       LOCK_UNTIL = NULL,
				       ERROR_MESSAGE = NULL,
				       UPDATED_AT = SYSTIMESTAMP
				 WHERE BATCH_ID = ?
				   AND LOCK_OWNER = ?
				""", claim.batchId(), claim.owner());

		if (updated == 0) {
			throw new IllegalStateException(
					"Could not mark batch " + claim.batchId() + " SUCCESS - lock no longer owned");
		}
	}

	private void markFailed(ClaimResult claim, Throwable t) {

		try {
			int updated = jdbcTemplate.update("""
					UPDATE GST_3B_BATCH_CONTROL
					   SET STATUS = 'FAILED',
					       ERROR_MESSAGE = ?,
					       COMPLETED_AT = SYSTIMESTAMP,
					       LOCK_UNTIL = NULL,
					       UPDATED_AT = SYSTIMESTAMP
					 WHERE BATCH_ID = ?
					   AND LOCK_OWNER = ?
					""", errorText(t), claim.batchId(), claim.owner());

			if (updated == 0) {
				log.warn("Batch {} not marked FAILED - lock no longer owned", claim.batchId());
			}

		} catch (Exception ex) {
			// batch stays RUNNING until LOCK_UNTIL expires, then becomes reclaimable
			log.error("Could not mark batch {} FAILED; it will be reclaimable after lock expiry", claim.batchId(), ex);
		}
	}

	private static String errorText(Throwable t) {

		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}

		String text = t.getClass().getSimpleName() + ": " + t.getMessage();
		if (root != t) {
			text += " | root: " + root.getClass().getSimpleName() + ": " + root.getMessage();
		}

		// ERROR_MESSAGE is bounded in bytes, not characters
		while (text.getBytes(StandardCharsets.UTF_8).length > MAX_ERROR_BYTES) {
			text = text.substring(0, (int) (text.length() * 0.9));
		}
		return text;
	}

	private static String elapsed(long startNanos) {

		Duration d = Duration.ofNanos(System.nanoTime() - startNanos);
		return String.format("%dh %02dm %02ds %03dms", d.toHours(), d.toMinutesPart(), d.toSecondsPart(),
				d.toMillisPart());
	}

	private static java.util.concurrent.ThreadFactory namedFactory(String prefix) {

		AtomicInteger n = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
			t.setDaemon(false);
			return t;
		};
	}

	@Override
	public void destroy() throws Exception {

		log.info("Shutting down batch executor");
		executor.shutdown();

		if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
			log.warn("Batches still running at shutdown - interrupting; they become reclaimable after lock expiry");
			executor.shutdownNow();
		}

		heartbeat.shutdownNow();
	}
}