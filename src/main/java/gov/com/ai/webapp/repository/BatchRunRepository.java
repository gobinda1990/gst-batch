package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import gov.com.ai.webapp.domain.BatchRunStatus;

import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class BatchRunRepository {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(String runId, String retPeriod, long totalEligible) {
        jdbcTemplate.update("""
            INSERT INTO GST_BATCH_RUN (
                RUN_ID, JOB_NAME, RET_PERIOD, STATUS,
                TOTAL_ELIGIBLE, SCANNED_COUNT, WRITTEN_COUNT,
                SKIPPED_COUNT, NOT_FILED_COUNT,
                FILED_LATE_COUNT, FILED_ON_TIME_COUNT,
                GSTR3A_ELIGIBLE_COUNT,
                STARTED_AT, UPDATED_AT
            ) VALUES (
                ?, 'RETURN_DEFAULTER', ?, 'RUNNING',
                ?, 0, 0, 0, 0, 0, 0, 0,
                SYSTIMESTAMP, SYSTIMESTAMP
            )
            """, runId, retPeriod, totalEligible);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(
            String runId,
            String checkpoint,
            long scanned,
            long written,
            long skipped,
            long notFiled,
            long filedLate,
            long filedOnTime,
            long gstr3aEligible) {

        int rows = jdbcTemplate.update("""
            UPDATE GST_BATCH_RUN
            SET LAST_GSTIN = ?,
                SCANNED_COUNT = ?,
                WRITTEN_COUNT = ?,
                SKIPPED_COUNT = ?,
                NOT_FILED_COUNT = ?,
                FILED_LATE_COUNT = ?,
                FILED_ON_TIME_COUNT = ?,
                GSTR3A_ELIGIBLE_COUNT = ?,
                UPDATED_AT = SYSTIMESTAMP
            WHERE RUN_ID = ?
            """,
            checkpoint, scanned, written, skipped,
            notFiled, filedLate, filedOnTime, gstr3aEligible,
            runId);

        requireSingleRow(rows, "progress", runId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(
            String runId,
            BatchRunStatus status,
            String message) {

        Objects.requireNonNull(status, "status must not be null");

        int rows = jdbcTemplate.update("""
            UPDATE GST_BATCH_RUN
            SET STATUS = ?,
                MESSAGE = ?,
                FINISHED_AT = SYSTIMESTAMP,
                UPDATED_AT = SYSTIMESTAMP
            WHERE RUN_ID = ?
            """,
            status.name(),
            truncate(message, 1000),
            runId);

        requireSingleRow(rows, "finish", runId);
    }

    private void requireSingleRow(int rows, String operation, String runId) {
        if (rows != 1) {
            throw new IllegalStateException(
                    "GST_BATCH_RUN " + operation + " affected " + rows + " rows for RUN_ID=" + runId);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
