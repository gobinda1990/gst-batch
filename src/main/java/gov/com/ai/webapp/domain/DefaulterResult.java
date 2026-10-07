package gov.com.ai.webapp.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DefaulterResult(
        String gstin,
        String retPeriod,
        LocalDate dueDate,
        FilingStatus filingStatus,
        LocalDate filingDate,
        int delayDays,
        BigDecimal taxableValue,
        BigDecimal outputTax,
        String lastFiledPeriod,
        LocalDate lastFilingDate,
        BigDecimal avgOutputTax3m,
        BigDecimal avgOutputTax6m,
        BigDecimal avgOutputTax12m,
        String growthStatus,
        String riskLevel,
        BigDecimal riskScore,
        BigDecimal defaultScore,
        DefaultLevel defaultLevel,
        int historicalDefaultCount,
        boolean gstr3aEligible,
        String stJuri,
        String officeName) {
}
