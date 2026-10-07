package gov.com.ai.webapp.exception;

import lombok.Getter;

@Getter
public class InvalidDefaulterRecordException extends RuntimeException {

    private static final long serialVersionUID = 1L;
	private final String gstin;
    private final String retPeriod;
    private final String errorCode;

    public InvalidDefaulterRecordException(
            String gstin,
            String retPeriod,
            String errorCode,
            String message) {
        super(message);
        this.gstin = gstin;
        this.retPeriod = retPeriod;
        this.errorCode = errorCode;
    }

    public InvalidDefaulterRecordException(
            String gstin,
            String retPeriod,
            String errorCode,
            String message,
            Throwable cause) {
        super(message, cause);
        this.gstin = gstin;
        this.retPeriod = retPeriod;
        this.errorCode = errorCode;
    }
}
