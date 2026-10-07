package gov.com.ai.webapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import gov.com.ai.webapp.domain.DefaultLevel;
import gov.com.ai.webapp.domain.DefaulterCandidate;
import gov.com.ai.webapp.domain.DefaulterResult;
import gov.com.ai.webapp.domain.FilingStatus;
import gov.com.ai.webapp.exception.InvalidDefaulterRecordException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ReturnDefaulterEngine {

	// 15 chars: state(2) PAN(10) entity(1) 'Z'-position(1, relaxed) checksum(1)
	private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z][0-9A-Z]{2}$");

	private static final Pattern PERIOD = Pattern.compile("^(0[1-9]|1[0-2])[0-9]{4}$");

	private static final BigDecimal MAX_SCORE = new BigDecimal("100");
	private static final BigDecimal WARNING = new BigDecimal("25");
	private static final BigDecimal HIGH = new BigDecimal("50");
	private static final BigDecimal CRITICAL = new BigDecimal("75");

	private static final Set<String> KNOWN_RISK_LEVELS = Set.of("NORMAL", "WARNING", "HIGH", "CRITICAL");

	private final Clock clock;

	public ReturnDefaulterEngine(Clock clock) {
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	public DefaulterResult calculate(DefaulterCandidate c) {
		validate(c);

		try {
			LocalDate today = LocalDate.now(clock);
			FilingCalculation filing = calculateFiling(c, today);

			BigDecimal score = score(c, filing);
			DefaultLevel level = level(score);

			// Existing (source) risk values may be null in the source system.
			// Never pass null through to the DB: default the score to 0.00 and
			// derive the level from the score when the source level is blank/unknown.
			BigDecimal existingScore = risk(c.existingRiskScore());
			String existingLevel = existingRiskLevel(c.existingRiskLevel(), existingScore);

			Objects.requireNonNull(score, "defaultScore must not be null");
			Objects.requireNonNull(level, "defaultLevel must not be null");

			boolean gstr3aEligible = filing.status() == FilingStatus.NOT_FILED && today.isAfter(c.dueDate());

			DefaulterResult result = new DefaulterResult(normalize(c.gstin()), c.retPeriod(), c.dueDate(),
					filing.status(), c.filingDate(), filing.delayDays(), money(c.taxableValue()), money(c.outputTax()),
					trim(c.lastFiledPeriod()), c.lastFilingDate(), money(c.avgOutputTax3m()), money(c.avgOutputTax6m()),
					money(c.avgOutputTax12m()), growth(c.growthStatus()), existingLevel, existingScore, score, level,
					Math.max(c.historicalDefaultCount(), 0), gstr3aEligible, trim(c.stJuri()), trim(c.officeName()));

			log.trace("Calculated gstin={} period={} status={} delay={} score={} level={} gstr3aEligible={}",
					result.gstin(), result.retPeriod(), result.filingStatus(), result.delayDays(),
					result.defaultScore(), result.defaultLevel(), result.gstr3aEligible());

			return result;

		} catch (InvalidDefaulterRecordException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			log.error("Defaulter calculation failed gstin={} period={} error={}", c.gstin(), c.retPeriod(),
					ex.getMessage(), ex);
			throw ex;
		}
	}

	private FilingCalculation calculateFiling(DefaulterCandidate c, LocalDate today) {

		if (c.filed()) {
			if (c.filingDate() == null) {
				throw invalid(c, "FILING_DATE_MISSING", "Return is marked filed but FILING_DATE is null");
			}

			int delay = days(ChronoUnit.DAYS.between(c.dueDate(), c.filingDate()));

			return delay == 0 ? new FilingCalculation(FilingStatus.FILED_ON_TIME, 0)
					: new FilingCalculation(FilingStatus.FILED_LATE, delay);
		}

		if (!today.isAfter(c.dueDate())) {
			return new FilingCalculation(FilingStatus.NOT_DUE, 0);
		}

		return new FilingCalculation(FilingStatus.NOT_FILED, days(ChronoUnit.DAYS.between(c.dueDate(), today)));
	}

	private BigDecimal score(DefaulterCandidate c, FilingCalculation filing) {

		BigDecimal score = BigDecimal.ZERO;

		if (filing.status() == FilingStatus.NOT_FILED) {
			score = score.add(new BigDecimal("40"));
		} else if (filing.status() == FilingStatus.FILED_LATE) {
			score = score.add(new BigDecimal("10"));
		}

		int delay = filing.delayDays();
		if (delay >= 90) {
			score = score.add(new BigDecimal("20"));
		} else if (delay >= 60) {
			score = score.add(new BigDecimal("15"));
		} else if (delay >= 30) {
			score = score.add(new BigDecimal("10"));
		} else if (delay > 0) {
			score = score.add(new BigDecimal("5"));
		}

		BigDecimal historical = BigDecimal.valueOf(Math.max(c.historicalDefaultCount(), 0))
				.multiply(new BigDecimal("4")).min(new BigDecimal("16"));

		score = score.add(historical);
		score = score.add(risk(c.existingRiskScore()).multiply(new BigDecimal("0.10")));

		String growth = growth(c.growthStatus());
		if ("STRONG_DECLINE".equals(growth)) {
			score = score.add(new BigDecimal("8"));
		} else if ("DECLINING".equals(growth)) {
			score = score.add(new BigDecimal("4"));
		}

		return score.max(BigDecimal.ZERO).min(MAX_SCORE).setScale(2, RoundingMode.HALF_UP);
	}

	private DefaultLevel level(BigDecimal score) {
		if (score.compareTo(CRITICAL) >= 0)
			return DefaultLevel.CRITICAL;
		if (score.compareTo(HIGH) >= 0)
			return DefaultLevel.HIGH;
		if (score.compareTo(WARNING) >= 0)
			return DefaultLevel.WARNING;
		return DefaultLevel.NORMAL;
	}

	/**
	 * Returns a non-null risk level. Uses the source level when it is one of the
	 * known values, otherwise derives it from the (already null-safe) score.
	 */
	private String existingRiskLevel(String raw, BigDecimal existingScore) {
		String v = upper(raw);
		if (v != null) {
			v = v.replace('-', '_').replace(' ', '_');
			if (KNOWN_RISK_LEVELS.contains(v)) {
				return v;
			}
			log.debug("Unknown existing risk level '{}', deriving from score {}", raw, existingScore);
		}
		return level(existingScore).name();
	}

	private void validate(DefaulterCandidate c) {
		if (c == null) {
			throw new InvalidDefaulterRecordException(null, null, "NULL_CANDIDATE", "Candidate must not be null");
		}

		String gstin = normalize(c.gstin());
		if (gstin == null || !GSTIN.matcher(gstin).matches()) {
			throw invalid(c, "INVALID_GSTIN", "Invalid GSTIN format");
		}

		if (c.retPeriod() == null || !PERIOD.matcher(c.retPeriod()).matches()) {
			throw invalid(c, "INVALID_RET_PERIOD", "RET_PERIOD must be MMYYYY");
		}

		if (c.dueDate() == null) {
			throw invalid(c, "DUE_DATE_MISSING", "Due date is required");
		}

		if (c.filed() && c.filingDate() == null) {
			throw invalid(c, "FILING_DATE_MISSING", "Filed return requires FILING_DATE");
		}
	}

	private InvalidDefaulterRecordException invalid(DefaulterCandidate c, String code, String message) {
		return new InvalidDefaulterRecordException(c == null ? null : c.gstin(), c == null ? null : c.retPeriod(), code,
				message);
	}

	private int days(long value) {
		if (value <= 0)
			return 0;
		return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
	}

	private BigDecimal risk(BigDecimal value) {
		if (value == null || value.signum() < 0)
			return BigDecimal.ZERO.setScale(2);
		return value.min(MAX_SCORE).setScale(2, RoundingMode.HALF_UP);
	}

	private BigDecimal money(BigDecimal value) {
		return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
	}

	private String growth(String value) {
		String v = upper(value);
		if (v == null)
			return null;
		v = v.replace('-', '_').replace(' ', '_');
		return "SHARP_DECLINE".equals(v) ? "STRONG_DECLINE" : v;
	}

	private String normalize(String value) {
		return upper(value);
	}

	private String upper(String value) {
		String v = trim(value);
		return v == null ? null : v.toUpperCase(Locale.ROOT);
	}

	private String trim(String value) {
		if (value == null)
			return null;
		String v = value.trim();
		return v.isEmpty() ? null : v;
	}

	private record FilingCalculation(FilingStatus status, int delayDays) {
	}
}
