package gov.com.ai.webapp.service;

import org.springframework.stereotype.Component;

import gov.com.ai.webapp.domain.OfficeMonthlyRevenue;

import java.math.*;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class OfficeRevenueGrowthCalculator {
	private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("MMuuuu");

	public GrowthResult calculate(OfficeMonthlyRevenue cur, List<OfficeMonthlyRevenue> history) {
		Objects.requireNonNull(cur);
		YearMonth cm = YearMonth.parse(cur.retPeriod(), F);
		Map<YearMonth, OfficeMonthlyRevenue> m = new HashMap<>();
		if (history != null)
			for (OfficeMonthlyRevenue r : history)
				if (r != null) {
					YearMonth ym = r.periodDate() != null ? YearMonth.from(r.periodDate())
							: YearMonth.parse(r.retPeriod(), F);
					if (m.putIfAbsent(ym, r) != null)
						throw new IllegalStateException("Duplicate office/month " + r.stJuri() + "/" + ym);
				}
		m.put(cm, cur);
		OfficeMonthlyRevenue pm = m.get(cm.minusMonths(1)), py = m.get(cm.minusYears(1));
		BigDecimal mo = g(cur.outputTax(), v(pm, true)), yo = g(cur.outputTax(), v(py, true)),
				mc = g(cur.cashTaxPaid(), v(pm, false)), yc = g(cur.cashTaxPaid(), v(py, false));
		return new GrowthResult(mo, yo, mc, yc, avg(m, cm, 3, true), avg(m, cm, 6, true), avg(m, cm, 12, true),
				avg(m, cm, 3, false), avg(m, cm, 6, false), avg(m, cm, 12, false), trend(mo, yo),
				status(mo != null ? mo : yo));
	}

	private BigDecimal g(BigDecimal c, BigDecimal p) {
		if (c == null || p == null || p.signum() == 0)
			return null;
		return c.subtract(p).divide(p.abs(), 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2,
				RoundingMode.HALF_UP);
	}

	private BigDecimal v(OfficeMonthlyRevenue r, boolean out) {
		return r == null ? null : (out ? r.outputTax() : r.cashTaxPaid());
	}

	private BigDecimal avg(Map<YearMonth, OfficeMonthlyRevenue> m, YearMonth cm, int n, boolean out) {
		BigDecimal s = BigDecimal.ZERO;
		int c = 0;
		for (int i = 0; i < n; i++) {
			BigDecimal v = v(m.get(cm.minusMonths(i)), out);
			if (v != null) {
				s = s.add(v);
				c++;
			}
		}
		return c == 0 ? null : s.divide(BigDecimal.valueOf(c), 2, RoundingMode.HALF_UP);
	}

	private String trend(BigDecimal m, BigDecimal y) {
		if (m == null && y == null)
			return "NO_BASE";
		BigDecimal a = BigDecimal.ONE;
		if (m != null && y != null) {
			if (m.compareTo(a) >= 0 && y.compareTo(a) >= 0)
				return "UPWARD";
			if (m.compareTo(a.negate()) <= 0 && y.compareTo(a.negate()) <= 0)
				return "DOWNWARD";
			if (m.abs().compareTo(a) < 0 && y.abs().compareTo(a) < 0)
				return "STABLE";
			return "MIXED";
		}
		BigDecimal x = m != null ? m : y;
		return x.compareTo(a) >= 0 ? "UPWARD" : x.compareTo(a.negate()) <= 0 ? "DOWNWARD" : "STABLE";
	}

	private String status(BigDecimal x) {
		if (x == null)
			return "NO_BASE";
		if (x.compareTo(BigDecimal.valueOf(15)) >= 0)
			return "HIGH";
		if (x.compareTo(BigDecimal.valueOf(5)) >= 0)
			return "MEDIUM";
		if (x.signum() > 0)
			return "LOW";
		if (x.compareTo(BigDecimal.ONE.negate()) <= 0)
			return "DECLINING";
		return "STABLE";
	}

	public record GrowthResult(BigDecimal momOutputGrowth, BigDecimal yoyOutputGrowth, BigDecimal momCashGrowth,
			BigDecimal yoyCashGrowth, BigDecimal avgOutputTax3m, BigDecimal avgOutputTax6m, BigDecimal avgOutputTax12m,
			BigDecimal avgCashTax3m, BigDecimal avgCashTax6m, BigDecimal avgCashTax12m, String growthTrend,
			String growthStatus) {
	}
}
