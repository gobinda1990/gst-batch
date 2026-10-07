package gov.com.ai.webapp.domain;

/**
 * Outcome of trying to claim a batch run.
 */
public record ClaimResult(boolean claimed, Long batchId, String owner, String reason) {

	public static ClaimResult granted(Long batchId, String owner) {
		return new ClaimResult(true, batchId, owner, "CLAIMED");
	}

	public static ClaimResult rejected(Long batchId, String reason) {
		return new ClaimResult(false, batchId, null, reason);
	}
}
