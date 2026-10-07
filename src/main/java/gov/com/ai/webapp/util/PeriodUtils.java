package gov.com.ai.webapp.util;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class PeriodUtils {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("MMyyyy");

	private PeriodUtils() {
	}

	public static YearMonth parse(String period) {

		if (period == null || !period.matches("\\d{6}")) {
			throw new RuntimeException("Invalid return period. Expected MMYYYY");
		}

		try {
			return YearMonth.parse(period, FORMATTER);
		} catch (DateTimeParseException ex) {
			throw new RuntimeException("Invalid return period: " + period);
		}
	}

	public static String format(YearMonth yearMonth) {
		return yearMonth.format(FORMATTER);
	}
}
