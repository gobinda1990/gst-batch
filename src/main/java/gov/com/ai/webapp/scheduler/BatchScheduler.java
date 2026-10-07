package gov.com.ai.webapp.scheduler;


import gov.com.ai.webapp.config.BatchProperties;
import gov.com.ai.webapp.service.DefaulterBatchService;
import gov.com.ai.webapp.service.MonthlyBatchService;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchScheduler {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("MMuuuu");
	
	private final MonthlyBatchService monthlybatchService;

	private final DefaulterBatchService batchService;
	
	private final BatchProperties properties;
	
	private final Clock clock = Clock.systemDefaultZone();

	/**
	 * Re-evaluates the previous return period.
	 *
	 * Disabled by default. Enable only after your operational schedule and due-date
	 * master are configured.
	 */
	@Scheduled(cron = "${gst.batch.defaulter.cron:0 30 2 * * *}", zone = "Asia/Kolkata")
	public void run() {

		if (!properties.getDefaulter().isSchedulerEnabled()) {
			return;
		}

		String period = YearMonth.now(ZoneId.of("Asia/Kolkata")).minusMonths(1).format(FORMAT);
		
		YearMonth previousMonth = YearMonth.now(clock).minusMonths(1);

		log.info("Starting scheduled GST 3B batch for {}", previousMonth);
		try {
			monthlybatchService.process(previousMonth);			
			batchService.run(period);
		} catch (RuntimeException ex) {
			log.error("Scheduled return-defaulter batch failed period={} error={}", period, ex.getMessage(), ex);
		}
	}
}