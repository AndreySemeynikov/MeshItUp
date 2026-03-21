package com.mesh.controlplane.canary;

import com.mesh.controlplane.config.CanaryProperties;
import com.mesh.controlplane.model.CanaryState;
import com.mesh.controlplane.model.CanaryState.Status;
import com.mesh.controlplane.store.AggregatedMetrics;
import com.mesh.controlplane.store.MetricsStore;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CanaryEvaluator {

  private static final Logger log = LoggerFactory.getLogger(CanaryEvaluator.class);

  private final CanaryManager canaryManager;
  private final MetricsStore metricsStore;
  private final CanaryProperties canaryProperties;

  public CanaryEvaluator(
      CanaryManager canaryManager, MetricsStore metricsStore, CanaryProperties canaryProperties) {
    this.canaryManager = canaryManager;
    this.metricsStore = metricsStore;
    this.canaryProperties = canaryProperties;
  }

  @Scheduled(fixedDelayString = "${canary.evaluation-interval:30}000")
  public void evaluate() {
    CanaryState state = canaryManager.getState();

    if (state.getStatus() != Status.IN_PROGRESS) {
      return;
    }

    String serviceId = state.getServiceId();
    Map<String, AggregatedMetrics> metrics = metricsStore.getAndReset();

    // Find canary and stable metrics
    String canaryKey = serviceId + ":canary";
    String stableKey = serviceId + ":stable";

    AggregatedMetrics canaryMetrics = metrics.get(canaryKey);
    AggregatedMetrics stableMetrics = metrics.get(stableKey);

    // Check if we have enough data
    if (canaryMetrics == null || canaryMetrics.getTotalRequests() == 0) {
      log.warn("Canary evaluation: insufficient data for {}, skipping", serviceId);
      state.setLastEvaluationAt(Instant.now());
      state.setLastEvaluationResult("SKIP: insufficient data (0 canary requests)");
      return;
    }

    double canaryErrorRate = canaryMetrics.getErrorRate();
    double stableErrorRate = stableMetrics != null ? stableMetrics.getErrorRate() : 0.0;
    double errorThreshold = state.getErrorThreshold();

    log.info(
        "Canary evaluation: {} canary errorRate={}%, stable errorRate={}%, threshold={}%",
        serviceId,
        String.format("%.2f", canaryErrorRate),
        String.format("%.2f", stableErrorRate),
        String.format("%.2f", errorThreshold));

    state.setLastEvaluationAt(Instant.now());

    if (canaryErrorRate > errorThreshold) {
      // Error rate exceeded — rollback
      String result =
          "ROLLBACK: canary error rate %.2f%% > threshold %.2f%%"
              .formatted(canaryErrorRate, errorThreshold);
      state.setLastEvaluationResult(result);
      log.warn(
          "Canary error rate {}% exceeds threshold {}%. Rolling back.",
          String.format("%.2f", canaryErrorRate), String.format("%.2f", errorThreshold));
      canaryManager.rollback();
    } else {
      // Success
      int successCount = state.getConsecutiveSuccessCount() + 1;
      state.setConsecutiveSuccessCount(successCount);

      String result =
          "OK: canary error rate %.2f%% <= threshold %.2f%% (success %d/%d)"
              .formatted(
                  canaryErrorRate,
                  errorThreshold,
                  successCount,
                  canaryProperties.successCountToPromote());
      state.setLastEvaluationResult(result);

      if (successCount >= canaryProperties.successCountToPromote()) {
        log.info("Canary passed {} consecutive evaluations. Increasing weight.", successCount);
        state.setConsecutiveSuccessCount(0);
        canaryManager.increaseWeight();
      }
    }
  }
}
