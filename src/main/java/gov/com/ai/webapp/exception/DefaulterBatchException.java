package gov.com.ai.webapp.exception;

public class DefaulterBatchException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public DefaulterBatchException(String message) {
		super(message);
	}

	public DefaulterBatchException(String message, Throwable cause) {
		super(message, cause);
	}
}
