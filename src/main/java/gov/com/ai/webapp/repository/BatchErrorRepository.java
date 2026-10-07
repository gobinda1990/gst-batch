package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class BatchErrorRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL = """
        INSERT INTO GST_BATCH_ERROR_LOG (
            RUN_ID, JOB_NAME, GSTIN, RET_PERIOD,
            ERROR_CODE, ERROR_MESSAGE, RETRY_COUNT, CREATED_AT
        ) VALUES (?, 'RETURN_DEFAULTER', ?, ?, ?, ?, ?, SYSTIMESTAMP)
        """;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(
            String runId,
            String gstin,
            String retPeriod,
            String errorCode,
            String message,
            int retryCount) {

        try {
            jdbcTemplate.update(
                    SQL,
                    runId,
                    gstin,
                    retPeriod,
                    truncate(errorCode, 100),
                    truncate(message, 1000),
                    retryCount
            );
        } catch (RuntimeException ex) {
            log.error(
                    "Unable to persist batch error runId={} gstin={} period={} originalCode={} persistenceError={}",
                    runId, gstin, retPeriod, errorCode, ex.getMessage(), ex);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
