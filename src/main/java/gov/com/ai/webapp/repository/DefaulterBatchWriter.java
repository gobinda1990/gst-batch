package gov.com.ai.webapp.repository;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import gov.com.ai.webapp.domain.DefaulterResult;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class DefaulterBatchWriter {

    private final JdbcTemplate jdbcTemplate;

    /*
     * Notice / response / Section-62 workflow columns are intentionally
     * NOT overwritten in WHEN MATCHED.
     */
    private static final String SQL = """
        MERGE INTO GST_3B_RETURN_DEFAULTER t
        USING (
            SELECT ? GSTIN, ? RET_PERIOD FROM DUAL
        ) s
        ON (t.GSTIN = s.GSTIN AND t.RET_PERIOD = s.RET_PERIOD)

        WHEN MATCHED THEN UPDATE SET
            t.DUE_DATE = ?,
            t.FILING_STATUS = ?,
            t.FILING_DATE = ?,
            t.DELAY_DAYS = ?,
            t.TAXABLE_VALUE = ?,
            t.OUTPUT_TAX = ?,
            t.LAST_FILED_PERIOD = ?,
            t.LAST_FILING_DATE = ?,
            t.AVG_OUTPUT_TAX_3M = ?,
            t.AVG_OUTPUT_TAX_6M = ?,
            t.AVG_OUTPUT_TAX_12M = ?,
            t.GROWTH_STATUS = ?,
            t.RISK_LEVEL = ?,
            t.RISK_SCORE = ?,
            t.DEFAULT_SCORE = ?,
            t.DEFAULT_LEVEL = ?,
            t.HISTORICAL_DEFAULT_COUNT = ?,
            t.GSTR3A_ELIGIBLE = ?,
            t.ST_JURI = ?,
            t.OFFICE_NAME = ?,
            t.ACTIVE_FLAG = 'Y',
            t.LAST_EVALUATED_AT = SYSTIMESTAMP,
            t.UPDATED_AT = SYSTIMESTAMP

        WHEN NOT MATCHED THEN INSERT (
            GSTIN, RET_PERIOD, DUE_DATE, FILING_STATUS, FILING_DATE,
            DELAY_DAYS, TAXABLE_VALUE, OUTPUT_TAX,
            LAST_FILED_PERIOD, LAST_FILING_DATE,
            AVG_OUTPUT_TAX_3M, AVG_OUTPUT_TAX_6M, AVG_OUTPUT_TAX_12M,
            GROWTH_STATUS, RISK_LEVEL, RISK_SCORE,
            DEFAULT_SCORE, DEFAULT_LEVEL, HISTORICAL_DEFAULT_COUNT,
            GSTR3A_ELIGIBLE, GSTR3A_STATUS,
            ST_JURI, OFFICE_NAME, ACTIVE_FLAG,
            FIRST_DETECTED_AT, LAST_EVALUATED_AT, CREATED_AT, UPDATED_AT
        ) VALUES (
            ?, ?, ?, ?, ?,
            ?, ?, ?,
            ?, ?,
            ?, ?, ?,
            ?, ?, ?,
            ?, ?, ?,
            ?, 'NOT_ISSUED',
            ?, ?, 'Y',
            SYSTIMESTAMP, SYSTIMESTAMP, SYSTIMESTAMP, SYSTIMESTAMP
        )
        """;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int writeChunk(List<DefaulterResult> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        long started = System.nanoTime();

        int[][] counts = jdbcTemplate.batchUpdate(
                SQL,
                rows,
                rows.size(),
                this::bind
        );

        int affected = 0;
        for (int[] batch : counts) {
            for (int value : batch) {
                if (value >= 0 || value == java.sql.Statement.SUCCESS_NO_INFO) {
                    affected++;
                }
            }
        }

        log.debug(
                "Defaulter chunk persisted requested={} affected={} durationMs={}",
                rows.size(), affected,
                (System.nanoTime() - started) / 1_000_000);

        return affected;
    }

    private void bind(PreparedStatement ps, DefaulterResult r)
            throws java.sql.SQLException {

        int i = 1;

        // USING
        ps.setString(i++, r.gstin());
        ps.setString(i++, r.retPeriod());

        // UPDATE
        ps.setDate(i++, Date.valueOf(r.dueDate()));
        ps.setString(i++, r.filingStatus().name());
        setDate(ps, i++, r.filingDate());
        ps.setInt(i++, r.delayDays());
        ps.setBigDecimal(i++, r.taxableValue());
        ps.setBigDecimal(i++, r.outputTax());
        ps.setString(i++, r.lastFiledPeriod());
        setDate(ps, i++, r.lastFilingDate());
        ps.setBigDecimal(i++, r.avgOutputTax3m());
        ps.setBigDecimal(i++, r.avgOutputTax6m());
        ps.setBigDecimal(i++, r.avgOutputTax12m());
        ps.setString(i++, r.growthStatus());
        ps.setString(i++, r.riskLevel());
        ps.setBigDecimal(i++, r.riskScore());
        ps.setBigDecimal(i++, r.defaultScore());
        ps.setString(i++, r.defaultLevel().name());
        ps.setInt(i++, r.historicalDefaultCount());
        ps.setString(i++, r.gstr3aEligible() ? "Y" : "N");
        ps.setString(i++, r.stJuri());
        ps.setString(i++, r.officeName());

        // INSERT
        ps.setString(i++, r.gstin());
        ps.setString(i++, r.retPeriod());
        ps.setDate(i++, Date.valueOf(r.dueDate()));
        ps.setString(i++, r.filingStatus().name());
        setDate(ps, i++, r.filingDate());
        ps.setInt(i++, r.delayDays());
        ps.setBigDecimal(i++, r.taxableValue());
        ps.setBigDecimal(i++, r.outputTax());
        ps.setString(i++, r.lastFiledPeriod());
        setDate(ps, i++, r.lastFilingDate());
        ps.setBigDecimal(i++, r.avgOutputTax3m());
        ps.setBigDecimal(i++, r.avgOutputTax6m());
        ps.setBigDecimal(i++, r.avgOutputTax12m());
        ps.setString(i++, r.growthStatus());
        ps.setString(i++, r.riskLevel());
        ps.setBigDecimal(i++, r.riskScore());
        ps.setBigDecimal(i++, r.defaultScore());
        ps.setString(i++, r.defaultLevel().name());
        ps.setInt(i++, r.historicalDefaultCount());
        ps.setString(i++, r.gstr3aEligible() ? "Y" : "N");
        ps.setString(i++, r.stJuri());
        ps.setString(i++, r.officeName());
    }

    private void setDate(PreparedStatement ps, int index, java.time.LocalDate value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setDate(index, Date.valueOf(value));
        }
    }
}
