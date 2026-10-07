package gov.com.ai.webapp.service;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import gov.com.ai.webapp.config.BatchProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;

@Service
public class XgboostRiskScoringService {

	private static final Logger log = LoggerFactory.getLogger(XgboostRiskScoringService.class);

	private static final int FEATURE_COUNT = 18;

	/** Upper bound of rows per native DMatrix, to keep memory predictable. */
	private static final int MAX_BATCH_ROWS = 50_000;

	private final BatchProperties properties;
	private final ResourceLoader resourceLoader;

	/** Write lock only while swapping the model; predictions share the read lock. */
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	private volatile Booster booster;

	public XgboostRiskScoringService(BatchProperties properties, ResourceLoader resourceLoader) {
		this.properties = properties;
		this.resourceLoader = resourceLoader;
	}

	/**
	 * Loads the model at startup (fails fast if it is missing, empty, or does not accept
	 * an 18-feature row). Can also be called again to hot-reload: the new model is loaded
	 * and tested OUTSIDE the lock, and the old one is kept if the new one is bad.
	 */
	@PostConstruct
	public void loadModel() {

		String modelPath = properties.getModel().getPath();
		long started = System.nanoTime();
		Booster loaded = null;
		int bytesRead;

		try {

			if (modelPath == null || modelPath.isBlank()) {
				throw new IllegalStateException("XGBoost model path is not configured");
			}

			Resource resource = resourceLoader.getResource(modelPath);

			if (!resource.exists()) {
				throw new IllegalStateException("XGBoost model not found: " + modelPath);
			}

			if (!resource.isReadable()) {
				throw new IllegalStateException("XGBoost model is not readable: " + modelPath);
			}

			byte[] modelBytes;

			try (InputStream input = resource.getInputStream()) {
				modelBytes = input.readAllBytes();
			}

			if (modelBytes.length == 0) {
				throw new IllegalStateException("XGBoost model file is empty: " + modelPath);
			}

			bytesRead = modelBytes.length;

			// XGBoost4J: loadModel(byte[]) - not loadModel(byte[], null)
			loaded = XGBoost.loadModel(modelBytes);

			if (loaded == null) {
				throw new IllegalStateException("XGBoost returned null Booster");
			}

			smokeTest(loaded);

		} catch (Exception ex) {

			dispose(loaded);

			log.error("Unable to load XGBoost model. name={}, path={}", properties.getModel().getName(), modelPath,
					ex);

			throw new IllegalStateException("Unable to load XGBoost model from " + modelPath, ex);
		}

		Booster old;

		lock.writeLock().lock();
		try {
			old = booster;
			booster = loaded;
		} finally {
			lock.writeLock().unlock();
		}

		dispose(old);

		log.info("XGBoost model {} : name={}, path={}, size={} bytes, features={}, took {} ms",
				old == null ? "loaded" : "reloaded", properties.getModel().getName(), modelPath, bytesRead,
				FEATURE_COUNT, (System.nanoTime() - started) / 1_000_000);
	}

	@PreDestroy
	public void shutdown() {

		lock.writeLock().lock();
		try {
			dispose(booster);
			booster = null;
		} finally {
			lock.writeLock().unlock();
		}
	}

	// ------------------------------------------------------------------
	// Prediction
	// ------------------------------------------------------------------

	/** Single-row prediction. Prefer {@link #predictBatch(List)} for anything more than a handful of rows. */
	public double predict(Gst3bFeatureVector feature) {

		String error = validationError(feature);

		if (error != null) {
			throw new IllegalArgumentException(error);
		}

		double score = predictBatch(List.of(feature))[0];

		if (Double.isNaN(score)) {
			throw new IllegalStateException("Invalid XGBoost prediction for row");
		}

		return score;
	}

	/**
	 * Scores many rows with ONE native DMatrix and ONE predict call (the per-row version
	 * allocated and freed a DMatrix for every row).
	 *
	 * @return scores in the same order as the input, each clamped to 0..1. A row that has
	 *         invalid features (null / infinite value) or a non-finite model output gets
	 *         {@code Double.NaN}; use {@link #validationError} to find out why.
	 * @throws IllegalStateException if the model is not loaded or the native call fails
	 *                               (systemic - not a bad row)
	 */
	public double[] predictBatch(List<Gst3bFeatureVector> features) {

		if (features == null) {
			throw new IllegalArgumentException("Feature list must not be null");
		}

		int total = features.size();
		double[] scores = new double[total];
		Arrays.fill(scores, Double.NaN);

		if (total == 0) {
			return scores;
		}

		long started = System.nanoTime();
		int scoredRows = 0;

		lock.readLock().lock();

		try {

			Booster model = booster;

			if (model == null) {
				throw new IllegalStateException("XGBoost model is not loaded");
			}

			for (int from = 0; from < total; from += MAX_BATCH_ROWS) {

				int to = Math.min(total, from + MAX_BATCH_ROWS);

				float[] data = new float[(to - from) * FEATURE_COUNT];
				int[] origin = new int[to - from];
				int valid = 0;

				for (int i = from; i < to; i++) {

					Gst3bFeatureVector f = features.get(i);

					if (f == null) {
						continue;
					}

					float[] row = buildFeatureVector(f);

					if (invalidFeature(row) != null) {
						continue;
					}

					System.arraycopy(row, 0, data, valid * FEATURE_COUNT, FEATURE_COUNT);
					origin[valid++] = i;
				}

				if (valid == 0) {
					continue;
				}

				float[] used = valid * FEATURE_COUNT == data.length ? data
						: Arrays.copyOf(data, valid * FEATURE_COUNT);

				float[][] prediction = runPredict(model, used, valid);

				for (int k = 0; k < valid; k++) {

					double score = prediction[k][0];

					if (!Double.isNaN(score) && !Double.isInfinite(score)) {
						scores[origin[k]] = clamp(score);
						scoredRows++;
					}
				}
			}

		} catch (RuntimeException ex) {
			throw ex;

		} catch (Exception ex) {
			throw new IllegalStateException("XGBoost prediction failed", ex);

		} finally {
			lock.readLock().unlock();
		}

		if (log.isDebugEnabled()) {
			log.debug("XGBoost batch: rows={}, scored={}, took {} ms", total, scoredRows,
					(System.nanoTime() - started) / 1_000_000);
		}

		return scores;
	}

	/**
	 * @return why this row cannot be scored, or {@code null} if it is fine
	 */
	public String validationError(Gst3bFeatureVector feature) {

		if (feature == null) {
			return "GST 3B feature vector must not be null";
		}

		return invalidFeature(buildFeatureVector(feature));
	}

	// ------------------------------------------------------------------
	// Internals
	// ------------------------------------------------------------------

	private float[][] runPredict(Booster model, float[] data, int rows) throws Exception {

		DMatrix matrix = null;

		try {

			// NaN marks "missing" for XGBoost
			matrix = new DMatrix(data, rows, FEATURE_COUNT, Float.NaN);

			float[][] prediction = model.predict(matrix);

			if (prediction == null || prediction.length != rows) {
				throw new IllegalStateException("XGBoost returned " + (prediction == null ? "null" : prediction.length)
						+ " predictions for " + rows + " rows");
			}

			for (float[] p : prediction) {
				if (p == null || p.length == 0) {
					throw new IllegalStateException("XGBoost returned an empty prediction row");
				}
			}

			return prediction;

		} finally {

			// DMatrix holds native memory
			if (matrix != null) {
				matrix.dispose();
			}
		}
	}

	/** Refuse a model that cannot score an 18-feature row, before it goes live. */
	private void smokeTest(Booster candidate) throws Exception {

		float[][] prediction = runPredict(candidate, new float[FEATURE_COUNT], 1);

		float value = prediction[0][0];

		if (Float.isNaN(value) || Float.isInfinite(value)) {
			throw new IllegalStateException("Model smoke test returned " + value);
		}
	}

	private void dispose(Booster b) {

		if (b == null) {
			return;
		}

		try {
			b.dispose();
		} catch (Exception ex) {
			log.warn("Could not dispose XGBoost booster: {}", ex.toString());
		}
	}

	/**
	 * Exact 18-feature vector used by the GST 3B model.
	 * Feature order MUST remain identical to the training order.
	 */
	private float[] buildFeatureVector(Gst3bFeatureVector feature) {

		return new float[] {

				(float) feature.taxableValue(), // 01
				(float) feature.totalOutputTax(), // 02
				(float) feature.eligibleItc(), // 03
				(float) feature.utilizedItc(), // 04
				(float) feature.reversedItc(), // 05
				(float) feature.ineligibleItc(), // 06
				(float) feature.excessItc(), // 07
				(float) feature.rcmTotalTax(), // 08
				(float) feature.cashTaxPaid(), // 09
				(float) feature.itcPaymentTotal(), // 10
				(float) feature.itcUtilizationRatio(), // 11
				(float) feature.cashPaymentRatio(), // 12
				(float) feature.itcPaymentRatio(), // 13
				(float) feature.itcToTaxRatio(), // 14
				(float) feature.nilSupplyRatio(), // 15
				(float) feature.rcmToTaxRatio(), // 16
				(float) feature.rcmItcRatio(), // 17
				(float) feature.rcmCashRatio() // 18
		};
	}

	/** NaN is allowed (XGBoost treats it as missing); infinite values are not. */
	private String invalidFeature(float[] values) {

		if (values.length != FEATURE_COUNT) {
			return "Invalid XGBoost feature count. Expected " + FEATURE_COUNT + " but received " + values.length;
		}

		for (int i = 0; i < values.length; i++) {
			if (Float.isInfinite(values[i])) {
				return "Feature " + (i + 1) + " contains infinite value";
			}
		}

		return null;
	}

	private double clamp(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}
}