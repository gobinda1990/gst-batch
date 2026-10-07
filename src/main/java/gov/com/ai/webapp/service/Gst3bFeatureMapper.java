package gov.com.ai.webapp.service;

import org.springframework.stereotype.Component;

@Component
public class Gst3bFeatureMapper {

    public Gst3bFeatureVector map(
            String gstin,
            String retPeriod,
            double taxableValue,
            double outputTax,
            double eligibleItc,
            double utilizedItc,
            double reversedItc,
            double ineligibleItc,
            double excessItc,
            double rcmTax,
            double cashPaid,
            double itcPayment,
            double nilSupplyRatio) {

        double safeOutput =
                Math.max(outputTax, 0.0);

        double safeEligibleItc =
                Math.max(eligibleItc, 0.0);

        double itcUtilizationRatio =
                ratio(utilizedItc, safeEligibleItc);

        double cashPaymentRatio =
                ratio(cashPaid, safeOutput);

        double itcPaymentRatio =
                ratio(itcPayment, safeOutput);

        double itcToTaxRatio =
                ratio(eligibleItc, safeOutput);

        double rcmToTaxRatio =
                ratio(rcmTax, safeOutput);

        double rcmItcRatio =
                ratio(rcmTax, safeEligibleItc);

        double rcmCashRatio =
                ratio(rcmTax, Math.max(cashPaid, 0.0));

        return new Gst3bFeatureVector(

                gstin,

                retPeriod,

                taxableValue,

                outputTax,

                eligibleItc,

                utilizedItc,

                reversedItc,

                ineligibleItc,

                excessItc,

                rcmTax,

                cashPaid,

                itcPayment,

                itcUtilizationRatio,

                cashPaymentRatio,

                itcPaymentRatio,

                itcToTaxRatio,

                nilSupplyRatio,

                rcmToTaxRatio,

                rcmItcRatio,

                rcmCashRatio
        );
    }

    private double ratio(
            double numerator,
            double denominator) {

        if (denominator <= 0.0) {
            return 0.0;
        }

        double value =
                numerator / denominator;

        if (value > 9.9999) {
            return 9.9999;
        }

        if (value < -9.9999) {
            return -9.9999;
        }

        return value;
    }
}
