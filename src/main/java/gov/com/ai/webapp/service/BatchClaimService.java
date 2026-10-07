package gov.com.ai.webapp.service;

import java.net.InetAddress;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.com.ai.webapp.config.BatchProperties;
import gov.com.ai.webapp.domain.ClaimResult;
import gov.com.ai.webapp.util.PeriodUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Claims a (batchType, period) run using a row in GST_3B_BATCH_CONTROL.
 *
 * Requires a UNIQUE constraint on (BATCH_TYPE, RET_PERIOD): SELECT ... FOR
 * UPDATE cannot lock a row that does not exist yet, so the constraint is what
 * stops two nodes from both inserting the first row.
 */
@Service
@Slf4j
public class BatchClaimService {

	private static final String SELECT_FOR_UPDATE = """
			SELECT BATCH_ID,
			       STATUS,
			       NVL(ATTEMPT_COUNT, 0) AS ATTEMPT_COUNT,
			       CASE WHEN LOCK_UNTIL IS NULL OR LOCK_UNTIL < SYSTIMESTAMP THEN 1 ELSE 0 END AS LOCK_EXPIRED
			  FROM GST_3B_BATCH_CONTROL
			 WHERE BATCH_TYPE = ?
			   AND RET_PERIOD = ?
			   FOR UPDATE
			""";

	private record Row(long batchId, String status, int attempts, boolean lockExpired) {
	}

	private final JdbcTemplate jdbcTemplate;
	private final BatchProperties properties;

	public BatchClaimService(JdbcTemplate jdbcTemplate, BatchProperties properties) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
	}

	@Transactional
	public ClaimResult claim(String batchType, YearMonth period) {

		String retPeriod = PeriodUtils.format(period);
		String owner = createOwner();

		Row row = lockRow(batchType, retPeriod);

		if (row == null) {
			try {
				long batchId = insertNew(batchType, retPeriod, owner);
				log.info("Batch {} created for {} ({}), owner={}", batchId, batchType, retPeriod, owner);
				return ClaimResult.granted(batchId, owner);

			} catch (DuplicateKeyException race) {
				// another node inserted first - lock and evaluate its row
				row = lockRow(batchType, retPeriod);
				if (row == null) {
					return ClaimResult.rejected(null, "CONCURRENT_CLAIM");
				}
			}
		}

		if ("SUCCESS".equals(row.status())) {
			return ClaimResult.rejected(row.batchId(), "ALREADY_COMPLETED");
		}

		if ("RUNNING".equals(row.status()) && !row.lockExpired()) {
			return ClaimResult.rejected(row.batchId(), "ALREADY_RUNNING");
		}

		if (row.attempts() >= properties.getMaxRetry()) {
			log.warn("Batch {} for {} reached max retry ({}). Manual reset required.", row.batchId(), retPeriod,
					properties.getMaxRetry());
			return ClaimResult.rejected(row.batchId(), "MAX_RETRY_REACHED");
		}

		if ("RUNNING".equals(row.status())) {
			log.warn("Batch {} for {} has an expired lock - reclaiming (previous attempt {})", row.batchId(), retPeriod,
					row.attempts());
		}

		jdbcTemplate.update("""
				UPDATE GST_3B_BATCH_CONTROL
				   SET STATUS = 'RUNNING',
				       ATTEMPT_COUNT = NVL(ATTEMPT_COUNT, 0) + 1,
				       LOCK_OWNER = ?,
				       LOCK_UNTIL = SYSTIMESTAMP + NUMTODSINTERVAL(?, 'MINUTE'),
				       STARTED_AT = SYSTIMESTAMP,
				       COMPLETED_AT = NULL,
				       ERROR_MESSAGE = NULL,
				       UPDATED_AT = SYSTIMESTAMP
				 WHERE BATCH_ID = ?
				""", owner, properties.getLockMinutes(), row.batchId());

		log.info("Batch {} claimed for {} ({}), attempt {}, owner={}", row.batchId(), batchType, retPeriod,
				row.attempts() + 1, owner);

		return ClaimResult.granted(row.batchId(), owner);
	}

	private Row lockRow(String batchType, String retPeriod) {

		List<Row> rows = jdbcTemplate.query(SELECT_FOR_UPDATE, (rs, i) -> new Row(rs.getLong("BATCH_ID"),
				rs.getString("STATUS"), rs.getInt("ATTEMPT_COUNT"), rs.getInt("LOCK_EXPIRED") == 1), batchType,
				retPeriod);

		return rows.isEmpty() ? null : rows.get(0);
	}

	private long insertNew(String batchType, String retPeriod, String owner) {

		Long batchId = jdbcTemplate.queryForObject("SELECT GST_3B_BATCH_SEQ.NEXTVAL FROM DUAL", Long.class);

		jdbcTemplate.update("""
				INSERT INTO GST_3B_BATCH_CONTROL
				(BATCH_ID, BATCH_TYPE, RET_PERIOD, STATUS, ATTEMPT_COUNT,
				 LOCK_OWNER, LOCK_UNTIL, STARTED_AT, CREATED_AT, UPDATED_AT)
				VALUES
				(?, ?, ?, 'RUNNING', 1,
				 ?, SYSTIMESTAMP + NUMTODSINTERVAL(?, 'MINUTE'), SYSTIMESTAMP, SYSTIMESTAMP, SYSTIMESTAMP)
				""", batchId, batchType, retPeriod, owner, properties.getLockMinutes());

		return batchId;
	}

	private String createOwner() {
		try {
			return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
		} catch (Exception ex) {
			return "unknown-" + UUID.randomUUID();
		}
	}
}
