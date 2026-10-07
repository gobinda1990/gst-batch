package gov.com.ai.webapp.service;

public record Gst3bFeatureVector(

		String gstin,

		String retPeriod,

		double taxableValue,

		double totalOutputTax,

		double eligibleItc,

		double utilizedItc,

		double reversedItc,

		double ineligibleItc,

		double excessItc,

		double rcmTotalTax,

		double cashTaxPaid,

		double itcPaymentTotal,

		double itcUtilizationRatio,

		double cashPaymentRatio,

		double itcPaymentRatio,

		double itcToTaxRatio,

		double nilSupplyRatio,

		double rcmToTaxRatio,

		double rcmItcRatio,

		double rcmCashRatio) {
}
