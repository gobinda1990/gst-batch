package gov.com.ai.webapp.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DefaulterCandidate(
        String gstin,
        String retPeriod,
        LocalDate dueDate,
        boolean filed,
        LocalDate filingDate,
        BigDecimal taxableValue,
        BigDecimal outputTax,
        String lastFiledPeriod,
        LocalDate lastFilingDate,
        BigDecimal avgOutputTax3m,
        BigDecimal avgOutputTax6m,
        BigDecimal avgOutputTax12m,
        String growthStatus,
        String existingRiskLevel,
        BigDecimal existingRiskScore,
        int historicalDefaultCount,
        String stJuri,
        String officeName) {
}
