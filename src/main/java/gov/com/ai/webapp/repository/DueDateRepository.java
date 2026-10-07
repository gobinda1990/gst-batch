package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import gov.com.ai.webapp.exception.DueDateNotConfiguredException;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class DueDateRepository {

	private final JdbcTemplate jdbcTemplate;

	private static final String SQL = """
			SELECT EFFECTIVE_DUE_DATE
			FROM GST_RETURN_DUE_DATE_MASTER
			WHERE RETURN_TYPE = 'GSTR3B'
			  AND RET_PERIOD = ?
			  AND ACTIVE_FLAG = 'Y'
			""";

	public LocalDate findRequiredDueDate(String retPeriod) {
		try {
			List<LocalDate> rows = jdbcTemplate.query(SQL, (rs, rowNum) -> {
				Date value = rs.getDate("EFFECTIVE_DUE_DATE");
				return value == null ? null : value.toLocalDate();
			}, retPeriod);

			List<LocalDate> valid = rows.stream().filter(java.util.Objects::nonNull).toList();

			if (valid.isEmpty()) {
				throw new DueDateNotConfiguredException(retPeriod);
			}

			if (valid.size() > 1) {
				throw new IllegalStateException("Multiple active due dates configured for RET_PERIOD=" + retPeriod);
			}

			return valid.get(0);

		} catch (DueDateNotConfiguredException | IllegalStateException ex) {
			throw ex;
		} catch (DataAccessException ex) {
			log.error("Unable to load due date retPeriod={} error={}", retPeriod, ex.getMessage(), ex);
			throw ex;
		}
	}
}
