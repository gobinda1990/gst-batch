package gov.com.ai.webapp.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class BatchExceptionHandler {

	@ExceptionHandler(BatchAlreadyRunningException.class)
	public ResponseEntity<Map<String, Object>> running(BatchAlreadyRunningException ex, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, "BATCH_ALREADY_RUNNING", ex.getMessage(), request);
	}

	@ExceptionHandler(DueDateNotConfiguredException.class)
	public ResponseEntity<Map<String, Object>> dueDate(DueDateNotConfiguredException ex, HttpServletRequest request) {
		return response(HttpStatus.UNPROCESSABLE_ENTITY, "DUE_DATE_NOT_CONFIGURED", ex.getMessage(), request);
	}

	@ExceptionHandler({ IllegalArgumentException.class, ConstraintViolationException.class })
	public ResponseEntity<Map<String, Object>> badRequest(Exception ex, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage(), request);
	}

	@ExceptionHandler(DefaulterBatchException.class)
	public ResponseEntity<Map<String, Object>> batch(DefaulterBatchException ex, HttpServletRequest request) {
		return response(HttpStatus.INTERNAL_SERVER_ERROR, "BATCH_FAILED", ex.getMessage(), request);
	}

	private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String message,
			HttpServletRequest request) {

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("timestamp", OffsetDateTime.now().toString());
		body.put("status", status.value());
		body.put("error", status.getReasonPhrase());
		body.put("code", code);
		body.put("message", message);
		body.put("path", request.getRequestURI());

		return ResponseEntity.status(status).body(body);
	}
}
