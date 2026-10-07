package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class BatchLockRepository {

    private static final String JOB = "RETURN_DEFAULTER";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Oracle 11g distributed lock based on a PK protected row.
     *
     * First removes only an expired lock for the same period, then inserts.
     * The PK guarantees that only one application node can acquire it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(
            String retPeriod,
            String owner,
            int lockMinutes) {

        jdbcTemplate.update("""
            DELETE FROM GST_BATCH_LOCK
            WHERE JOB_NAME = ?
              AND BUSINESS_KEY = ?
              AND EXPIRES_AT < SYSTIMESTAMP
            """,
            JOB, retPeriod);

        try {
            jdbcTemplate.update("""
                INSERT INTO GST_BATCH_LOCK (
                    JOB_NAME, BUSINESS_KEY, LOCKED_BY,
                    LOCKED_AT, EXPIRES_AT
                ) VALUES (
                    ?, ?, ?, SYSTIMESTAMP,
                    SYSTIMESTAMP + NUMTODSINTERVAL(?, 'MINUTE')
                )
                """,
                JOB, retPeriod, owner, lockMinutes);

            return true;

        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(
            String retPeriod,
            String owner,
            int lockMinutes) {

        int updated = jdbcTemplate.update("""
            UPDATE GST_BATCH_LOCK
            SET EXPIRES_AT =
                    SYSTIMESTAMP + NUMTODSINTERVAL(?, 'MINUTE')
            WHERE JOB_NAME = ?
              AND BUSINESS_KEY = ?
              AND LOCKED_BY = ?
            """,
            lockMinutes, JOB, retPeriod, owner);

        if (updated != 1) {
            throw new IllegalStateException(
                    "Batch lock lost for RET_PERIOD=" + retPeriod);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String retPeriod, String owner) {
        int deleted = jdbcTemplate.update("""
            DELETE FROM GST_BATCH_LOCK
            WHERE JOB_NAME = ?
              AND BUSINESS_KEY = ?
              AND LOCKED_BY = ?
            """,
            JOB, retPeriod, owner);

        log.debug("Batch lock release period={} owner={} deleted={}",
                retPeriod, owner, deleted);
    }
}
