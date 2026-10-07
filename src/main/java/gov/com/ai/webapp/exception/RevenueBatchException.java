package gov.com.ai.webapp.exception;

public class RevenueBatchException extends RuntimeException {
	
	private static final long serialVersionUID = 1L;

	public RevenueBatchException(String m) {
		super(m);
	}

	public RevenueBatchException(String m, Throwable t) {
		super(m, t);
	}
}
