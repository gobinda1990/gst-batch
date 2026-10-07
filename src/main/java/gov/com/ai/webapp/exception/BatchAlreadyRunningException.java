package gov.com.ai.webapp.exception;

public class BatchAlreadyRunningException extends RuntimeException {
    private static final long serialVersionUID = 1L;

	public BatchAlreadyRunningException(String retPeriod) {
        super("Return-defaulter batch is already running for RET_PERIOD=" + retPeriod);
    }
}
