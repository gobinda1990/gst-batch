package gov.com.ai.webapp.exception;

public class DueDateNotConfiguredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

	public DueDateNotConfiguredException(String retPeriod) {
        super("No active GSTR-3B due date configured for RET_PERIOD=" + retPeriod);
    }
}
