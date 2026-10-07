package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import gov.com.ai.webapp.config.BatchProperties;
import java.net.InetAddress;

@Repository
@RequiredArgsConstructor
public class RevenueBatchLockRepository {
	private final NamedParameterJdbcTemplate jdbc;
	private final BatchProperties props;

	public boolean acquire(String key, String token) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("k", key).addValue("t", token);
		jdbc.update(
				"DELETE FROM GST_OFFICE_REVENUE_BATCH_LOCK WHERE JOB_NAME='OFFICE_REVENUE' AND BUSINESS_KEY=:k AND EXPIRES_AT<SYSTIMESTAMP",
				p);
		try {
			jdbc.update(
					"INSERT INTO GST_OFFICE_REVENUE_BATCH_LOCK(JOB_NAME,BUSINESS_KEY,LOCK_TOKEN,LOCKED_BY,LOCKED_AT,EXPIRES_AT) VALUES('OFFICE_REVENUE',:k,:t,:h,SYSTIMESTAMP,SYSTIMESTAMP+NUMTODSINTERVAL(:m,'MINUTE'))",
					p.addValue("h", host()).addValue("m", props.getLockMinutes()));
			return true;
		} catch (DuplicateKeyException e) {
			return false;
		}
	}

	public void release(String key, String token) {
		jdbc.update(
				"DELETE FROM GST_OFFICE_REVENUE_BATCH_LOCK WHERE JOB_NAME='OFFICE_REVENUE' AND BUSINESS_KEY=:k AND LOCK_TOKEN=:t",
				new MapSqlParameterSource().addValue("k", key).addValue("t", token));
	}

	private String host() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (Exception e) {
			return "unknown";
		}
	}
}
