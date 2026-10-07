package gov.com.ai.webapp.domain;

import java.time.Instant;

public record RevenueBatchResponse(String runId, String retPeriod, String status, long sourceRows, long sourceGstins,
		int aggregatedOffices, int mergedRows, int growthRowsUpdated, int staleRowsDeleted, long unmappedRegistrations,
		long unmappedJurisdictions, Instant startedAt, Instant completedAt, long durationMs, String message) {
}
