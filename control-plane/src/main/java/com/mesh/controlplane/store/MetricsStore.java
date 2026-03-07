package com.mesh.controlplane.store;

import com.mesh.controlplane.model.MetricsEntry;
import com.mesh.controlplane.model.MetricsReport;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MetricsStore {

  private static final Logger log = LoggerFactory.getLogger(MetricsStore.class);

  // Key: "destination:version" (e.g. "inventory-service:canary")
  private final ConcurrentHashMap<String, AggregatedMetrics> currentWindow =
      new ConcurrentHashMap<>();

  /** Ingest a metrics report from a sidecar proxy. */
  public void ingest(MetricsReport report) {
    for (MetricsEntry entry : report.entries()) {
      String key = entry.destination() + ":" + entry.version();
      currentWindow
          .computeIfAbsent(key, k -> new AggregatedMetrics())
          .add(entry.requestCount(), entry.errorCount(), entry.avgLatencyMs());
    }
    log.info(
        "Metrics report received from {}: {} entries", report.proxyId(), report.entries().size());
  }

  /**
   * Get a snapshot of current metrics and reset counters. Called by CanaryEvaluator on each
   * evaluation tick.
   */
  public Map<String, AggregatedMetrics> getAndReset() {
    Map<String, AggregatedMetrics> snapshot = new HashMap<>(currentWindow);
    currentWindow.clear();
    return snapshot;
  }
}
