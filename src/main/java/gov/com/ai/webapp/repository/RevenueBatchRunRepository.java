package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RevenueBatchRunRepository {
	private final NamedParameterJdbcTemplate jdbc;

	public void start(String id, String p) {
		jdbc.update(
				"INSERT INTO GST_OFFICE_REVENUE_BATCH_RUN(RUN_ID,RET_PERIOD,STATUS,STARTED_AT,UPDATED_AT) VALUES(:i,:p,'RUNNING',SYSTIMESTAMP,SYSTIMESTAMP)",
				new MapSqlParameterSource().addValue("i", id).addValue("p", p));
	}

	public void success(String id, long rows, long gstins, int offices, long ur, long uj, long dup) {
		jdbc.update(
				"UPDATE GST_OFFICE_REVENUE_BATCH_RUN SET STATUS='SUCCESS',SOURCE_ROWS=:r,SOURCE_GSTINS=:g,OFFICES_PROCESSED=:o,UNMAPPED_REGISTRATIONS=:ur,UNMAPPED_JURISDICTIONS=:uj,DUPLICATE_MAPPINGS=:d,FINISHED_AT=SYSTIMESTAMP,UPDATED_AT=SYSTIMESTAMP WHERE RUN_ID=:i",
				new MapSqlParameterSource().addValue("i", id).addValue("r", rows).addValue("g", gstins)
						.addValue("o", offices).addValue("ur", ur).addValue("uj", uj).addValue("d", dup));
	}

	public void fail(String id, String msg) {
		jdbc.update(
				"UPDATE GST_OFFICE_REVENUE_BATCH_RUN SET STATUS='FAILED',ERROR_MESSAGE=:e,FINISHED_AT=SYSTIMESTAMP,UPDATED_AT=SYSTIMESTAMP WHERE RUN_ID=:i",
				new MapSqlParameterSource().addValue("i", id).addValue("e",
						msg == null ? "Unknown" : msg.substring(0, Math.min(2000, msg.length()))));
	}
}
