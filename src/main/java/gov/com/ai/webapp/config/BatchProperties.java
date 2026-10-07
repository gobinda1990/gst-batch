package gov.com.ai.webapp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gst.batch")
public class BatchProperties {

	/**
	 * Enable / disable monthly GST 3B batch.
	 */
	private boolean enabled = true;

	/**
	 * Number of GSTINs processed in one chunk.
	 */
	@Min(50)
	@Max(10_000)
	private int chunkSize = 500;

	/**
	 * JDBC fetch size.
	 */
	@Min(100)
	@Max(10_000)
	private int fetchSize = 1_000;

	/**
	 * JDBC write batch size.
	 */
	@Min(50)
	@Max(10_000)
	private int writeBatchSize = 500;

	/**
	 * Processing period.
	 *
	 * Format: MMYYYY
	 *
	 * Empty = previous month.
	 */
	@Pattern(regexp = "^$|^(0[1-9]|1[0-2])\\d{4}$", message = "GST batch processPeriod must be MMYYYY")
	private String processPeriod = "";

	/**
	 * Number of historical months required for GSTIN growth calculation.
	 */
	@Min(1)
	@Max(60)
	private int lookbackMonths = 12;

	/**
	 * Maximum records processed by one execution.
	 *
	 * 0 = unlimited.
	 */
	@Min(0)
	@Max(10_000_000)
	private int maxRecords = 0;

	/**
	 * Maximum retry attempts for transient failures.
	 */
	@Min(0)
	@Max(10)
	private int maxRetry = 5;

	/**
	 * Batch lock duration in minutes.
	 */
	@Min(1)
	@Max(1_440)
	private int lockMinutes = 120;

	/**
	 * Continue processing other chunks when one chunk/GSTIN fails.
	 */
	private boolean continueOnError = true;

	/**
	 * Persist/audit batch failures.
	 */
	private boolean auditFailures = true;

	/**
	 * XGBoost model configuration.
	 */
	@Valid
	@NotNull
	private Model model = new Model();

	/**
	 * Risk threshold configuration.
	 */
	@Valid
	@NotNull
	private Risk risk = new Risk();

	/**
	 * Detailed retry configuration.
	 */
	@Valid
	@NotNull
	private Retry retry = new Retry();

	/**
	 * Batch execution configuration.
	 */
	@Valid
	@NotNull
	private Execution execution = new Execution();

	/**
	 * Defaulter batch execution configuration.
	 */
	@Valid
	@NotNull
	private Defaulter defaulter = new Defaulter();

	/**
	 * Revenue batch execution configuration.
	 */
	@Valid
	@NotNull
	private Revenue revenue = new Revenue();

	// =========================================================
	// BACKWARD COMPATIBILITY & DELEGATE METHODS
	// =========================================================

	public int getMaxRetry() {
		return maxRetry;
	}

	public int getLockMinutes() {
		return lockMinutes;
	}

	// Delegates for legacy direct calls on properties instance for Defaulter
	public int normalizedChunkSize() {
		return defaulter.normalizedChunkSize();
	}

	public int normalizedLockMinutes() {
		return defaulter.normalizedLockMinutes();
	}

	public int normalizedMaxRetry() {
		return defaulter.normalizedMaxRetry();
	}

	public long normalizedRetryBackoffMs() {
		return defaulter.normalizedRetryBackoffMs();
	}

	// =========================================================
	// XGBOOST MODEL
	// =========================================================

	@Getter
	@Setter
	public static class Model {

		private String path = "classpath:models/gst_risk_model.json";
		private String name = "GST_3B_XGB";
		private String version = "1.0.0";
		private String featureVersion = "18F_V1";

		@Min(1)
		@Max(1_000)
		private int featureCount = 18;
	}

	// =========================================================
	// RISK
	// =========================================================

	@Getter
	@Setter
	public static class Risk {

		@Min(0)
		@Max(1)
		private double criticalThreshold = 0.80;

		@Min(0)
		@Max(1)
		private double highThreshold = 0.60;

		@Min(0)
		@Max(1)
		private double mediumThreshold = 0.35;
	}

	// =========================================================
	// RETRY
	// =========================================================

	@Getter
	@Setter
	public static class Retry {

		@Min(0)
		@Max(10)
		private int maxAttempts = 3;

		@Min(100)
		@Max(60_000)
		private long backoffMs = 1_000;

		@Min(1)
		@Max(10)
		private int multiplier = 2;
	}

	// =========================================================
	// EXECUTION
	// =========================================================

	@Getter
	@Setter
	public static class Execution {

		private boolean runOnStartup = false;
		private String cron = "0 0 2 1 * *";
		private boolean preventOverlap = true;
		private String lockName = "GST_3B_MONTHLY_BATCH";
	}

	// =========================================================
	// DEFAULTER BATCH CONFIGURATION
	// =========================================================

	@Getter
	@Setter
	public static class Defaulter {

		/**
		 * Number of GSTINs read and written per transaction.
		 */
		@Min(1)
		@Max(5_000)
		private int chunkSize = 1_000;

		/**
		 * Maximum number of retries for transient database failures.
		 */
		@Min(0)
		@Max(10)
		private int maxRetry = 3;

		/**
		 * Initial retry backoff in milliseconds.
		 */
		@Min(100)
		@Max(30_000)
		private long retryBackoffMs = 1_000L;

		/**
		 * Database lock expiry in minutes.
		 */
		@Min(10)
		@Max(1_440)
		private int lockMinutes = 120;

		/**
		 * Scheduler is disabled by default.
		 */
		private boolean schedulerEnabled = false;

		/**
		 * Scheduler expression. Example: 0 30 2 * * *
		 */
		private String cron = "0 30 2 * * *";

		public int normalizedChunkSize() {
			if (chunkSize <= 0) {
				return 1000;
			}
			return Math.min(chunkSize, 5000);
		}

		public int normalizedMaxRetry() {
			return Math.max(0, Math.min(maxRetry, 10));
		}

		public long normalizedRetryBackoffMs() {
			return Math.max(100L, Math.min(retryBackoffMs, 30_000L));
		}

		public int normalizedLockMinutes() {
			return Math.max(10, Math.min(lockMinutes, 24 * 60));
		}
	}

	// =========================================================
	// REVENUE BATCH CONFIGURATION
	// =========================================================

	@Getter
	@Setter
	public static class Revenue {

		private boolean schedulerEnabled = true;
		private String schedulerCron = "0 30 2 * * *";

		@Min(10)
		@Max(1_440)
		private int lockMinutes = 60;

		@Min(0)
		@Max(10)
		private int maxRetry = 3;

		@Min(100)
		@Max(60_000)
		private long retryBackoffMs = 2_000L;

		@Min(1)
		@Max(120)
		private int historyMonths = 12;

		private String masterOfficeNameColumn = "JURISDICTION_NAME";

		public int normalizedLockMinutes() {
			return Math.max(10, Math.min(lockMinutes, 24 * 60));
		}

		public int normalizedMaxRetry() {
			return Math.max(0, Math.min(maxRetry, 10));
		}

		public long normalizedRetryBackoffMs() {
			return Math.max(100L, Math.min(retryBackoffMs, 60_000L));
		}
	}
}