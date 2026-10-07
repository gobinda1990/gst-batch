package gov.com.ai.webapp.service;

import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import gov.com.ai.webapp.repository.GstGrowthRepository;
import gov.com.ai.webapp.repository.GstGrowthRepository.GstinRange;
import gov.com.ai.webapp.util.PeriodUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Computes GST 3B growth analytics for one period in GSTIN chunks. Every chunk
 * is its own transaction, so undo stays small, progress is visible, and a
 * failure never rolls back chunks that already succeeded. Re-running is safe
 * (MERGE).
 */
@Service
@Slf4j
public class GstGrowthService {

    private final GstGrowthRepository repository;
    private final TransactionTemplate chunkTx;
    private final int chunkSize;
    private final int chunkRetries;

    public GstGrowthService(GstGrowthRepository repository,
            PlatformTransactionManager txManager,
            @Value("${gst.growth.chunk-size:50000}") int chunkSize,
            @Value("${gst.growth.chunk-retries:2}") int chunkRetries) {

        this.repository = repository;
        this.chunkSize = Math.max(1_000, chunkSize);
        this.chunkRetries = Math.max(0, chunkRetries);

        this.chunkTx = new TransactionTemplate(txManager);
        this.chunkTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public long countSourceRecords(String retPeriod) {
        return repository.countSourceRecords(retPeriod);
    }

    public long processGrowth(YearMonth period, Long batchId) {
        return processGrowth(period, batchId, () -> false);
    }

    /**
     * @param shouldStop checked before every chunk (e.g. batch lock lost) so a run
     *                  that no longer owns the batch stops early instead of
     *                  finishing blindly
     * @return number of rows merged
     */
    public long processGrowth(YearMonth period, Long batchId, BooleanSupplier shouldStop) {

        String retPeriod = PeriodUtils.format(period);
        long started = System.nanoTime();

        long total = repository.countSourceRecords(retPeriod);

        if (total == 0) {
            log.warn("Growth[batch={}, period={}] no source rows in GST_RET_3B_SUMMARY - nothing to do",
                    batchId, retPeriod);
            return 0;
        }

        long missingDate = repository.countMissingPeriodDate(retPeriod);
        if (missingDate > 0) {
            log.warn(
                    "Growth[batch={}, period={}] {} source rows have NULL PERIOD_DATE in GST_RET_3B_SUMMARY - "
                            + "using first day of RET_PERIOD instead; please fix the upstream load",
                    batchId, retPeriod, missingDate);
        }

        List<GstinRange> chunks = repository.planChunks(retPeriod, total, chunkSize);

        log.info("Growth[batch={}, period={}] start: rows={}, chunks={}, chunkSize~{}",
                batchId, retPeriod, total, chunks.size(), chunkSize);

        long merged = 0;
        long rowsDone = 0;

        for (int i = 0; i < chunks.size(); i++) {

            if (shouldStop.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException(
                        String.format("Growth aborted before chunk %d/%d (batch lock lost or shutdown). "
                                + "%d rows already merged - safe to re-run", i + 1, chunks.size(), merged));
            }

            GstinRange range = chunks.get(i);
            long chunkStart = System.nanoTime();

            int rows = mergeWithRetry(retPeriod, range, i + 1, chunks.size(), batchId);

            merged += rows;
            rowsDone += range.rows();

            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            long etaMs = rowsDone == 0 ? 0 : elapsedMs * (total - rowsDone) / rowsDone;

            log.info(
                    "Growth[batch={}, period={}] chunk {}/{} gstin {}..{} merged={} in {} ms | "
                            + "progress {}/{} ({}%) elapsed={} ETA~{}",
                    batchId, retPeriod, i + 1, chunks.size(),
                    range.from(), range.to(), rows,
                    Duration.ofNanos(System.nanoTime() - chunkStart).toMillis(),
                    rowsDone, total, rowsDone * 100 / total, fmt(elapsedMs), fmt(etaMs));
        }

        log.info("Growth[batch={}, period={}] done: merged={} of {} source rows in {}",
                batchId, retPeriod, merged, total, fmt(Duration.ofNanos(System.nanoTime() - started).toMillis()));

        return merged;
    }

    private int mergeWithRetry(String retPeriod, GstinRange range, int chunkNo, int chunkCount, Long batchId) {

        for (int attempt = 0;; attempt++) {

            try {
                Integer rows = chunkTx.execute(status -> repository.mergeChunk(retPeriod, range));
                return rows == null ? 0 : rows;

            } catch (TransientDataAccessException ex) {
                if (attempt >= chunkRetries) {
                    throw failure(retPeriod, range, chunkNo, chunkCount, ex);
                }

                long waitMs = 2_000L * (attempt + 1);
                log.warn(
                        "Growth[batch={}, period={}] chunk {}/{} transient failure (attempt {}/{}): {} - retrying in {} ms",
                        batchId, retPeriod, chunkNo, chunkCount, attempt + 1, chunkRetries + 1,
                        ex.getMessage(), waitMs);

                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw failure(retPeriod, range, chunkNo, chunkCount, ie);
                }

            } catch (RuntimeException ex) {
                throw failure(retPeriod, range, chunkNo, chunkCount, ex);
            }
        }
    }

    private IllegalStateException failure(String retPeriod, GstinRange range, int chunkNo, int chunkCount, Exception cause) {
        return new IllegalStateException(
                String.format("Growth merge failed: period=%s chunk %d/%d gstin %s..%s",
                        retPeriod, chunkNo, chunkCount, range.from(), range.to()),
                cause);
    }

    private static String fmt(long ms) {
        Duration d = Duration.ofMillis(ms);
        return String.format("%02d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }
}