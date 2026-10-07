package gov.com.ai.webapp.service;

import java.time.YearMonth;
import java.util.function.BooleanSupplier;

public interface RiskScoringService {

	void processRisk(YearMonth period, Long batchId);

	/**
	 * @param shouldStop checked before every chunk (e.g. batch lock lost) so a run that
	 *                   no longer owns the batch stops early
	 */
	default void processRisk(YearMonth period, Long batchId, BooleanSupplier shouldStop) {
		processRisk(period, batchId);
	}
}