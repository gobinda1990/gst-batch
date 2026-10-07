package gov.com.ai.webapp.exception;

public class RevenueRequestException extends RuntimeException {
	
	private static final long serialVersionUID = 1L;

	public RevenueRequestException(String m) {
		super(m);
	}

	public RevenueRequestException(String m, Throwable t) {
		super(m, t);
	}
}
