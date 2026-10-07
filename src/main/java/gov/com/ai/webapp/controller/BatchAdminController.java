package gov.com.ai.webapp.controller;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import gov.com.ai.webapp.domain.ClaimResult;
import gov.com.ai.webapp.domain.DefaulterBatchResponse;
import gov.com.ai.webapp.domain.RevenueBatchResponse;
import gov.com.ai.webapp.service.DefaulterBatchService;
import gov.com.ai.webapp.service.MonthlyBatchService;
import gov.com.ai.webapp.service.OfficeRevenueBatchService;
import gov.com.ai.webapp.util.PeriodUtils;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/admin/return-3b")
@Slf4j
@RequiredArgsConstructor
public class BatchAdminController {
	
	private static final String PERIOD = "(0[1-9]|1[0-2])20\\d{2}";
	
	private static final String PERIOD_MSG = "must be in MMYYYY format, e.g. 032025";
	
	private static final CacheControl NO_STORE = CacheControl.noStore();
	
	/** Single-node guard. For several app instances use ShedLock or a DB lock row instead. */
	private final AtomicBoolean batchRunning = new AtomicBoolean(false);	

	private final MonthlyBatchService batchService;
	
	private final OfficeRevenueBatchService batch;	
	
	private final DefaulterBatchService defaultBatchService;

	@PostMapping("/month-growth/run/{retPeriod}")
	public ResponseEntity<Map<String, Object>> run(@PathVariable String retPeriod) {

		YearMonth period;

		try {
			period = PeriodUtils.parse(retPeriod);
		} catch (RuntimeException ex) {
			return body(HttpStatus.BAD_REQUEST, retPeriod, "INVALID_PERIOD", "Expected format MMyyyy, e.g. 042025",
					null);
		}

		ClaimResult result;

		try {
			result = batchService.submit(period);

		} catch (RejectedExecutionException ex) {
			return body(HttpStatus.SERVICE_UNAVAILABLE, retPeriod, "BUSY", "Batch queue is full, retry later", null);
		}

		if (!result.claimed()) {
			return body(HttpStatus.CONFLICT, retPeriod, result.reason(), "Batch not started", result.batchId());
		}

		log.info("Batch {} accepted for {}", result.batchId(), retPeriod);

		return body(HttpStatus.ACCEPTED, retPeriod, "ACCEPTED", "GST 3B monthly batch submitted", result.batchId());
	}
	
	
	@PostMapping("/month-revenue/run")
	public ResponseEntity<RevenueBatchResponse> runRevenueBatch(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod) {
		if (!batchRunning.compareAndSet(false, true)) {
			log.warn("Office revenue batch rejected, already running requestedPeriod={}", retPeriod);
			throw new ResponseStatusException(HttpStatus.CONFLICT, "An office revenue batch is already running");
		}
		long t0 = System.nanoTime();
		try {
			log.info("Office revenue batch started period={}", retPeriod);
			RevenueBatchResponse result = batch.run(retPeriod);
			log.info("Office revenue batch finished period={} elapsedMs={}", retPeriod, ms(t0));
			return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
		} catch (RuntimeException e) {
			log.error("Office revenue batch failed period={} elapsedMs={} cause={}", retPeriod, ms(t0), e.toString());
			throw e; // stack trace is logged once by the exception handler
		} finally {
			batchRunning.set(false);
		}
	}

	
	
	private static long ms(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}
	
	@PostMapping("/defaulter-batch/run")
	public ResponseEntity<DefaulterBatchResponse> defaultBatchRun(
			@RequestParam @Pattern(regexp = "(0[1-9]|1[0-2])\\d{4}", message = "retPeriod must be MMYYYY") String retPeriod) {

		log.info("Manual return-defaulter batch requested retPeriod={}", retPeriod);

		return ResponseEntity.ok(defaultBatchService.run(retPeriod));
	}

	private ResponseEntity<Map<String, Object>> body(HttpStatus http, String retPeriod, String status, String message,
			Long batchId) {

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("status", status);
		m.put("retPeriod", retPeriod);
		m.put("message", message);
		if (batchId != null) {
			m.put("batchId", batchId);
		}
		return ResponseEntity.status(http).body(m);
	}
	
	@GetMapping("/status/{retPeriod}")
	public ResponseEntity<?> status(@PathVariable String retPeriod) {

		YearMonth period;

		try {
			period = PeriodUtils.parse(retPeriod);
		} catch (RuntimeException ex) {
			return body(HttpStatus.BAD_REQUEST, retPeriod, "INVALID_PERIOD", "Expected format MMyyyy, e.g. 042025",
					null);
		}

		List<Map<String, Object>> rows = batchService.status(period);

		if (rows.isEmpty()) {
			return body(HttpStatus.NOT_FOUND, retPeriod, "NOT_FOUND", "No batch recorded for this period", null);
		}

		return ResponseEntity.ok(rows.get(0));
	}
}
