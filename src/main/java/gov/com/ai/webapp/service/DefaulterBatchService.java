package gov.com.ai.webapp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import gov.com.ai.webapp.config.BatchProperties;
import gov.com.ai.webapp.domain.BatchRunStatus;
import gov.com.ai.webapp.domain.DefaulterBatchResponse;
import gov.com.ai.webapp.domain.DefaulterCandidate;
import gov.com.ai.webapp.domain.DefaulterResult;
import gov.com.ai.webapp.exception.BatchAlreadyRunningException;
import gov.com.ai.webapp.exception.DefaulterBatchException;
import gov.com.ai.webapp.exception.InvalidDefaulterRecordException;
import gov.com.ai.webapp.repository.BatchErrorRepository;
import gov.com.ai.webapp.repository.BatchLockRepository;
import gov.com.ai.webapp.repository.BatchRunRepository;
import gov.com.ai.webapp.repository.DefaulterBatchWriter;
import gov.com.ai.webapp.repository.DefaulterSourceRepository;
import gov.com.ai.webapp.repository.DueDateRepository;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaulterBatchService {

	private static final Pattern PERIOD = Pattern.compile("^(0[1-9]|1[0-2])\\d{4}$");

	private final DueDateRepository dueDateRepository;
	private final DefaulterSourceRepository sourceRepository;
	private final ReturnDefaulterEngine engine;
	private final DefaulterBatchWriter writer;
	private final BatchErrorRepository errorRepository;
	private final BatchRunRepository runRepository;
	private final BatchLockRepository lockRepository;
	private final BatchProperties properties;

	/** Mutable run counters, kept together so they can be saved on failure too. */
	private static final class Counters {
		long scanned;
		long written;
		long skipped;
		long notFiled;
		long filedLate;
		long filedOnTime;
		long gstr3aEligible;
	}

	public DefaulterBatchResponse run(String retPeriod) {

		validatePeriod(retPeriod);

		final String runId = UUID.randomUUID().toString();
		final String owner = resolveOwner() + ":" + runId;
		int chunkSize = properties.getDefaulter().normalizedChunkSize();
		int lockMinutes = properties.getDefaulter().normalizedLockMinutes();

		if (!lockRepository.tryAcquire(retPeriod, owner, lockMinutes)) {
			throw new BatchAlreadyRunningException(retPeriod);
		}

		final long started = System.nanoTime();
		final Counters ct = new Counters();
		boolean runCreated = false;
		String checkpoint = null;

		try {
			final LocalDate dueDate = retry("findDueDate", () -> dueDateRepository.findRequiredDueDate(retPeriod));
			final long totalEligible = retry("countEligibleTaxpayers",
					() -> sourceRepository.countEligibleTaxpayers(retPeriod));

			runRepository.start(runId, retPeriod, totalEligible);
			runCreated = true;

			log.info("Return-defaulter batch START runId={} retPeriod={} dueDate={} totalEligible={} chunkSize={}",
					runId, retPeriod, dueDate, totalEligible, chunkSize);

			while (true) {

				final String pageCheckpoint = checkpoint;

				List<DefaulterCandidate> candidates = retry("fetchCandidates",
						() -> sourceRepository.fetchCandidates(retPeriod, dueDate, pageCheckpoint, chunkSize));

				if (candidates == null || candidates.isEmpty()) {
					break;
				}

				List<DefaulterResult> valid = new ArrayList<>(candidates.size());

				for (DefaulterCandidate candidate : candidates) {
					ct.scanned++;
					processCandidate(runId, retPeriod, candidate, valid, ct);
				}

				if (!valid.isEmpty()) {
					final List<DefaulterResult> chunk = valid;
					/*
					 * Oracle JDBC may return SUCCESS_NO_INFO. The writer therefore reports the
					 * number of accepted batch statements rather than relying on row counts. The
					 * writer must be idempotent (MERGE / delete+insert) because a transient
					 * failure re-runs the whole chunk.
					 */
					ct.written += retry("writeChunk", () -> writer.writeChunk(chunk));
				}

				String nextCheckpoint = candidates.get(candidates.size() - 1).gstin();

				// Guard against an endless loop when the source query is not strictly
				// ordered / paged by GSTIN (null or repeated checkpoint).
				if (nextCheckpoint == null || nextCheckpoint.equals(checkpoint)) {
					throw new DefaulterBatchException("Checkpoint did not advance (checkpoint=" + nextCheckpoint
							+ "). Source query must be ordered by GSTIN and return GSTIN > checkpoint.");
				}
				checkpoint = nextCheckpoint;

				runRepository.progress(runId, checkpoint, ct.scanned, ct.written, ct.skipped, ct.notFiled,
						ct.filedLate, ct.filedOnTime, ct.gstr3aEligible);

				lockRepository.heartbeat(retPeriod, owner, lockMinutes);

				log.info(
						"Return-defaulter batch PROGRESS runId={} period={} checkpoint={} scanned={}/{} written={} skipped={} notFiled={} filedLate={} gstr3aEligible={}",
						runId, retPeriod, checkpoint, ct.scanned, totalEligible, ct.written, ct.skipped, ct.notFiled,
						ct.filedLate, ct.gstr3aEligible);
			}

			BatchRunStatus status = ct.skipped > 0 ? BatchRunStatus.COMPLETED_WITH_ERRORS : BatchRunStatus.COMPLETED;

			String message = ct.skipped > 0 ? "Completed with quarantined invalid source records"
					: "Completed successfully";

			// Persist final counters before marking the run finished.
			runRepository.progress(runId, checkpoint, ct.scanned, ct.written, ct.skipped, ct.notFiled, ct.filedLate,
					ct.filedOnTime, ct.gstr3aEligible);
			runRepository.finish(runId, status, message);

			long durationMs = (System.nanoTime() - started) / 1_000_000;

			log.info(
					"Return-defaulter batch COMPLETE runId={} period={} status={} scanned={} written={} skipped={} notFiled={} filedLate={} filedOnTime={} gstr3aEligible={} durationMs={}",
					runId, retPeriod, status, ct.scanned, ct.written, ct.skipped, ct.notFiled, ct.filedLate,
					ct.filedOnTime, ct.gstr3aEligible, durationMs);

			return new DefaulterBatchResponse(runId, retPeriod, dueDate, status.name(), totalEligible, ct.scanned,
					ct.written, ct.skipped, ct.notFiled, ct.filedLate, ct.filedOnTime, ct.gstr3aEligible, durationMs,
					message);

		} catch (RuntimeException ex) {

			if (runCreated) {
				// Best effort: save whatever was counted, then mark FAILED.
				try {
					runRepository.progress(runId, checkpoint, ct.scanned, ct.written, ct.skipped, ct.notFiled,
							ct.filedLate, ct.filedOnTime, ct.gstr3aEligible);
				} catch (RuntimeException progressEx) {
					log.error("Unable to save final counters runId={} error={}", runId, progressEx.getMessage(),
							progressEx);
				}
				try {
					runRepository.finish(runId, BatchRunStatus.FAILED, failureMessage(ex));
				} catch (RuntimeException logEx) {
					log.error("Unable to mark failed batch runId={} error={}", runId, logEx.getMessage(), logEx);
				}
			}

			log.error(
					"Return-defaulter batch FAILED runId={} period={} checkpoint={} scanned={} written={} skipped={} error={}",
					runId, retPeriod, checkpoint, ct.scanned, ct.written, ct.skipped, ex.getMessage(), ex);

			throw ex instanceof DefaulterBatchException ? ex
					: new DefaulterBatchException("Return-defaulter batch failed for RET_PERIOD=" + retPeriod, ex);

		} finally {
			try {
				lockRepository.release(retPeriod, owner);
			} catch (RuntimeException ex) {
				log.error("Unable to release batch lock runId={} period={} owner={} error={}", runId, retPeriod, owner,
						ex.getMessage(), ex);
			}
		}
	}

	/**
	 * One bad row must never abort the whole run: business-invalid rows and
	 * unexpected calculation errors are both quarantined and counted as skipped.
	 */
	private void processCandidate(String runId, String retPeriod, DefaulterCandidate candidate,
			List<DefaulterResult> valid, Counters ct) {
		try {
			DefaulterResult result = engine.calculate(candidate);
			valid.add(result);

			switch (result.filingStatus()) {
			case NOT_FILED -> ct.notFiled++;
			case FILED_LATE -> ct.filedLate++;
			case FILED_ON_TIME -> ct.filedOnTime++;
			case NOT_DUE -> {
				// intentionally not counted as defaulter
			}
			}

			if (result.gstr3aEligible()) {
				ct.gstr3aEligible++;
			}

		} catch (InvalidDefaulterRecordException ex) {
			ct.skipped++;
			quarantine(runId, ex.getGstin(), ex.getRetPeriod(), ex.getErrorCode(), ex.getMessage());

		} catch (RuntimeException ex) {
			ct.skipped++;
			String gstin = candidate == null ? null : candidate.gstin();
			log.error("Unexpected calculation error runId={} gstin={} period={} error={}", runId, gstin, retPeriod,
					ex.getMessage(), ex);
			quarantine(runId, gstin, retPeriod, "CALCULATION_ERROR", failureMessage(ex));
		}
	}

	private void quarantine(String runId, String gstin, String retPeriod, String code, String message) {
		log.warn("Defaulter row quarantined runId={} gstin={} period={} code={} reason={}", runId, gstin, retPeriod,
				code, message);
		try {
			errorRepository.save(runId, gstin, retPeriod, code, message, 0);
		} catch (RuntimeException ex) {
			// Do not lose the whole run because the error table write failed;
			// the row is still counted as skipped and logged above.
			log.error("Unable to save quarantine record runId={} gstin={} code={} error={}", runId, gstin, code,
					ex.getMessage(), ex);
		}
	}

	private <T> T retry(String operation, Supplier<T> supplier) {

		int maxRetry = properties.getDefaulter().getMaxRetry();
		long backoff = properties.getDefaulter().normalizedRetryBackoffMs();

		for (int attempt = 0;; attempt++) {
			try {
				return supplier.get();

			} catch (TransientDataAccessException | RecoverableDataAccessException ex) {

				if (attempt >= maxRetry) {
					log.error("Transient Oracle operation exhausted retries operation={} attempts={} error={}",
							operation, attempt + 1, ex.getMessage(), ex);
					throw ex;
				}

				long wait = Math.min(backoff * (1L << Math.min(attempt, 5)), 30_000L);

				log.warn("Transient Oracle failure operation={} attempt={}/{} retryInMs={} error={}", operation,
						attempt + 1, maxRetry + 1, wait, ex.getMessage());

				sleep(wait);
			}
		}
	}

	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new DefaulterBatchException("Batch retry interrupted", ex);
		}
	}

	private void validatePeriod(String period) {
		if (period == null || !PERIOD.matcher(period).matches()) {
			throw new IllegalArgumentException("Invalid RET_PERIOD=" + period + ". Expected MMYYYY.");
		}
	}

	private String failureMessage(Throwable ex) {
		String msg = ex.getMessage();
		return Objects.requireNonNullElse(msg, ex.getClass().getSimpleName());
	}

	private String resolveOwner() {
		try {
			return InetAddress.getLocalHost().getHostName() + ":" + ManagementFactory.getRuntimeMXBean().getName();
		} catch (Exception ex) {
			return "unknown-node";
		}
	}
}
