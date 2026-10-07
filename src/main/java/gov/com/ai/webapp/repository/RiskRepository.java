package gov.com.ai.webapp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import gov.com.ai.webapp.config.BatchProperties;
import gov.com.ai.webapp.service.Gst3bFeatureVector;

@Repository
public class RiskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final BatchProperties properties;

    public RiskRepository(
            JdbcTemplate jdbcTemplate,
            BatchProperties properties) {

        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void save(
            Gst3bFeatureVector feature,
            double score,
            Long batchId) {

        String level =
                resolveLevel(score);

        jdbcTemplate.update(
                """
                MERGE INTO GST_RET_3B_RISK_PROFILE tgt

                USING
                (
                    SELECT
                        ? GSTIN,
                        ? RET_PERIOD,
                        ? RISK_SCORE,
                        ? RISK_LEVEL,
                        ? MODEL_NAME,
                        ? MODEL_VERSION,
                        ? FEATURE_VERSION,
                        ? BATCH_ID
                    FROM DUAL
                ) src

                ON
                (
                    tgt.GSTIN = src.GSTIN
                    AND tgt.RET_PERIOD = src.RET_PERIOD
                )

                WHEN MATCHED THEN UPDATE SET

                    tgt.RISK_SCORE =
                        src.RISK_SCORE,

                    tgt.RISK_LEVEL =
                        src.RISK_LEVEL,

                    tgt.MODEL_NAME =
                        src.MODEL_NAME,

                    tgt.MODEL_VERSION =
                        src.MODEL_VERSION,

                    tgt.FEATURE_VERSION =
                        src.FEATURE_VERSION,

                    tgt.BATCH_ID =
                        src.BATCH_ID,

                    tgt.PROCESSED_AT =
                        SYSTIMESTAMP,

                    tgt.UPDATED_AT =
                        SYSTIMESTAMP

                WHEN NOT MATCHED THEN INSERT
                (
                    ID,
                    GSTIN,
                    RET_PERIOD,
                    RISK_SCORE,
                    RISK_LEVEL,
                    MODEL_NAME,
                    MODEL_VERSION,
                    FEATURE_VERSION,
                    BATCH_ID,
                    PROCESSED_AT,
                    CREATED_AT,
                    UPDATED_AT
                )
                VALUES
                (
                    GST_3B_RISK_SEQ.NEXTVAL,
                    src.GSTIN,
                    src.RET_PERIOD,
                    src.RISK_SCORE,
                    src.RISK_LEVEL,
                    src.MODEL_NAME,
                    src.MODEL_VERSION,
                    src.FEATURE_VERSION,
                    src.BATCH_ID,
                    SYSTIMESTAMP,
                    SYSTIMESTAMP,
                    SYSTIMESTAMP
                )
                """,

                feature.gstin(),
                feature.retPeriod(),
                score,
                level,
                properties.getModel().getName(),
                properties.getModel().getVersion(),
                properties.getModel().getFeatureVersion(),
                batchId
        );
    }

    private String resolveLevel(double score) {

    	if (score >= properties.getRisk().getCriticalThreshold()) {
			return "CRITICAL";
		}

		if (score >= properties.getRisk().getHighThreshold()) {
			return "HIGH";
		}

		if (score >= properties.getRisk().getMediumThreshold()) {
			return "MEDIUM";
		}

        return "LOW";
    }
}
