package gov.com.ai.webapp.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import lombok.extern.slf4j.Slf4j;

@Repository
@Slf4j
public class GstGrowthRepository {

	/**
	 * Stored when there is no prior-year value to compare with (previously
	 * mislabelled 'STABLE').
	 */
	static final String NO_BASELINE_TREND = "NA";

	/**
	 * One slice of the period, split by GSTIN so each slice can be merged and
	 * committed on its own.
	 */
	public record GstinRange(String from, String to, long rows) {
	}

	private record Metric(String column, String growthName, String avgName) {
	}

	private static final List<Metric> METRICS = List.of(new Metric("TAXABLE_VALUE", "TAXABLE", "TAXABLE"),
			new Metric("TOTAL_OUTPUT_TAX", "OUTPUT_TAX", "OUTPUT_TAX"), new Metric("ELIGIBLE_ITC", "ITC", "ITC"),
			new Metric("CASH_TAX_PAID", "CASH", null));

	private static final int[] AVG_MONTHS = { 3, 6, 12 };

	/**
	 * Source rows can arrive with PERIOD_DATE = NULL, but the target column is NOT
	 * NULL (ORA-01400). Fall back to the first day of RET_PERIOD (format MMyyyy,
	 * e.g. 042025 -> 01-APR-2025).
	 */
	private static final String CUR_DATE = "NVL(cur.PERIOD_DATE, TO_DATE('01' || cur.RET_PERIOD, 'DDMMYYYY'))";
	private static final String C_DATE = "NVL(c.PERIOD_DATE, TO_DATE('01' || c.RET_PERIOD, 'DDMMYYYY'))";

	private static final String MERGE_SQL = buildMergeSql();

	private final JdbcTemplate jdbcTemplate;

	public GstGrowthRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public long countSourceRecords(String retPeriod) {
		Long value = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				  FROM GST_RET_3B_SUMMARY
				 WHERE RET_PERIOD = ?
				""", Long.class, retPeriod);

		return value == null ? 0 : value;
	}

	/**
	 * Source rows with NULL PERIOD_DATE - the date is derived from RET_PERIOD when
	 * merging.
	 */
	public long countMissingPeriodDate(String retPeriod) {
		Long value = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				  FROM GST_RET_3B_SUMMARY
				 WHERE RET_PERIOD = ?
				   AND PERIOD_DATE IS NULL
				""", Long.class, retPeriod);

		return value == null ? 0 : value;
	}

	/** Splits the period into ~equal GSTIN ranges of about chunkSize rows each. */
	public List<GstinRange> planChunks(String retPeriod, long totalRows, int chunkSize) {
		int buckets = (int) Math.max(1, (totalRows + chunkSize - 1) / chunkSize);

		return jdbcTemplate.query("""
				SELECT MIN(GSTIN) AS LO, MAX(GSTIN) AS HI, COUNT(*) AS CNT
				  FROM (SELECT GSTIN, NTILE(?) OVER (ORDER BY GSTIN) AS BKT
				          FROM GST_RET_3B_SUMMARY
				         WHERE RET_PERIOD = ?)
				 GROUP BY BKT
				 ORDER BY LO
				""", (rs, i) -> new GstinRange(rs.getString("LO"), rs.getString("HI"), rs.getLong("CNT")), buckets,
				retPeriod);
	}

	/** Idempotent: re-running a chunk just re-merges the same values. */
	public int mergeChunk(String retPeriod, GstinRange range) {
		if (log.isDebugEnabled()) {
			log.debug("MERGE chunk period={} gstin {}..{}", retPeriod, range.from(), range.to());
		}

		// The 3 binds appear twice: once for the history sub-query, once for the main
		// query.
		return jdbcTemplate.update(MERGE_SQL, retPeriod, range.from(), range.to(), retPeriod, range.from(), range.to());
	}

	// ------------------------------------------------------------------
	// SQL generation. The original repeated the same CASE / sub-query 30+ times;
	// generating it keeps the metrics consistent and avoids copy-paste drift.
	//
	// Performance: the 9 correlated AVG sub-queries (one index probe per row each)
	// and the two LEFT JOINs are replaced by ONE self-join over the 13-month
	// window, aggregated per GSTIN.
	// ------------------------------------------------------------------
	private static String buildMergeSql() {

		List<String> cols = new ArrayList<>(List.of("PERIOD_DATE", "TAXABLE_VALUE", "OUTPUT_TAX", "ELIGIBLE_ITC",
				"UTILIZED_ITC", "CASH_TAX_PAID", "RCM_TOTAL_TAX"));

		StringBuilder inner = new StringBuilder("""
				SELECT cur.GSTIN, cur.RET_PERIOD, %s AS PERIOD_DATE,
				       NVL(cur.TAXABLE_VALUE,0)    AS TAXABLE_VALUE,
				       NVL(cur.TOTAL_OUTPUT_TAX,0) AS OUTPUT_TAX,
				       NVL(cur.ELIGIBLE_ITC,0)     AS ELIGIBLE_ITC,
				       NVL(cur.UTILIZED_ITC,0)     AS UTILIZED_ITC,
				       NVL(cur.CASH_TAX_PAID,0)    AS CASH_TAX_PAID,
				       NVL(cur.RCM_TOTAL_TAX,0)    AS RCM_TOTAL_TAX
				""".formatted(CUR_DATE));

		StringBuilder hist = new StringBuilder("SELECT c.GSTIN, " + C_DATE + " AS CUR_DATE");

		// MoM then YoY growth
		for (String basis : new String[] { "MOM", "YOY" }) {
			for (Metric m : METRICS) {
				String name = basis + "_" + m.growthName() + "_GROWTH";
				String ref = "h." + ("MOM".equals(basis) ? "PREV" : "YOY") + "_" + m.column();
				inner.append(",\n       ").append(growthExpr(m.column(), ref)).append(" AS ").append(name);
				cols.add(name);
			}
		}

		// history sub-query columns
		for (Metric m : METRICS) {
			hist.append(",\n       MAX(CASE WHEN x.PERIOD_DATE = ADD_MONTHS(" + C_DATE + ",-1) THEN x.")
					.append(m.column()).append(" END) AS PREV_").append(m.column());
			hist.append(",\n       MAX(CASE WHEN x.PERIOD_DATE = ADD_MONTHS(" + C_DATE + ",-12) THEN x.")
					.append(m.column()).append(" END) AS YOY_").append(m.column());

			if (m.avgName() != null) {
				for (int months : AVG_MONTHS) {
					hist.append(",\n       AVG(CASE WHEN x.PERIOD_DATE >= ADD_MONTHS(" + C_DATE + ",-")
							.append(months - 1).append(") THEN x.").append(m.column()).append(" END) AS A")
							.append(months).append("_").append(m.column());

					String name = "AVG_" + months + "M_" + m.avgName();
					inner.append(",\n       h.A").append(months).append("_").append(m.column()).append(" AS ")
							.append(name);
					cols.add(name);
				}
			}
		}

		inner.append(",\n       cur.FILING_DELAY_DAYS");
		cols.add("FILING_DELAY_DAYS");
		cols.add("GROWTH_TREND");

		String updateSet = cols.stream().map(c -> "tgt." + c + " = src." + c).collect(Collectors.joining(",\n       "));

		String insertCols = String.join(", ", cols);
		String insertVals = cols.stream().map(c -> "src." + c).collect(Collectors.joining(", "));

		StringBuilder sql = new StringBuilder();

		sql.append("MERGE INTO GST_3B_GROWTH_ANALYTICS tgt ").append(" USING (SELECT q.*, ")
				.append(" CASE WHEN q.YOY_TAXABLE_GROWTH IS NULL THEN '").append(NO_BASELINE_TREND).append("' ")
				.append(" WHEN q.YOY_TAXABLE_GROWTH >= 0.20 THEN 'STRONG_GROWTH' ")
				.append(" WHEN q.YOY_TAXABLE_GROWTH >= 0.05 THEN 'GROWTH' ")
				.append(" WHEN q.YOY_TAXABLE_GROWTH > -0.05 THEN 'STABLE' ")
				.append(" WHEN q.YOY_TAXABLE_GROWTH > -0.20 THEN 'DECLINE' ")
				.append(" ELSE 'STRONG_DECLINE' END AS GROWTH_TREND ").append(" FROM (").append(inner)
				.append(" FROM GST_RET_3B_SUMMARY cur ").append(" LEFT JOIN (").append(hist)
				.append(" FROM GST_RET_3B_SUMMARY c ").append(" JOIN GST_RET_3B_SUMMARY x ")
				.append(" ON x.GSTIN = c.GSTIN ").append(" AND x.PERIOD_DATE BETWEEN ADD_MONTHS(").append(C_DATE)
				.append(", -12) AND ").append(C_DATE).append(" ").append(" WHERE c.RET_PERIOD = ? ")
				.append(" AND c.GSTIN BETWEEN ? AND ? ").append(" GROUP BY c.GSTIN, ").append(C_DATE)
				.append(") h ON h.GSTIN = cur.GSTIN AND h.CUR_DATE = ").append(CUR_DATE).append(" ")
				.append(" WHERE cur.RET_PERIOD = ? ").append(" AND cur.GSTIN BETWEEN ? AND ? ").append(" ) q ")
				.append(" ) src ").append(" ON (tgt.GSTIN = src.GSTIN AND tgt.RET_PERIOD = src.RET_PERIOD) ")
				.append(" WHEN MATCHED THEN UPDATE SET ").append(updateSet).append(", tgt.UPDATED_AT = SYSTIMESTAMP ")
				.append(" WHEN NOT MATCHED THEN INSERT (ID, GSTIN, RET_PERIOD, ").append(insertCols)
				.append(", CREATED_AT, UPDATED_AT) ")
				.append(" VALUES (GST_3B_GROWTH_SEQ.NEXTVAL, src.GSTIN, src.RET_PERIOD, ").append(insertVals)
				.append(", SYSTIMESTAMP, SYSTIMESTAMP)");

		return sql.toString();
	}

	private static String growthExpr(String column, String prevRef) {
		return "CASE WHEN NVL(" + prevRef + ",0) = 0 THEN NULL " + "ELSE (NVL(cur." + column + ",0) - " + prevRef
				+ ") / ABS(" + prevRef + ") END";
	}
}