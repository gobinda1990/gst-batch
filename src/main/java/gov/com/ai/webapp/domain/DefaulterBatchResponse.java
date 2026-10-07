package gov.com.ai.webapp.domain;

import java.time.LocalDate;

public record DefaulterBatchResponse(String runId, String retPeriod, LocalDate dueDate, String status,
		long totalEligible, long scanned, long written, long skipped, long notFiled, long filedLate, long filedOnTime,
		long gstr3aEligible, long durationMs, String message) {
}
