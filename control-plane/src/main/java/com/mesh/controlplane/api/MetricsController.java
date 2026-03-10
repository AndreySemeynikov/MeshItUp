package com.mesh.controlplane.api;

import com.mesh.controlplane.model.MetricsReport;
import com.mesh.controlplane.store.MetricsStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Metrics ingestion API")
public class MetricsController {

  private final MetricsStore metricsStore;

  public MetricsController(MetricsStore metricsStore) {
    this.metricsStore = metricsStore;
  }

  @PostMapping("/report")
  @Operation(summary = "Accept a metrics report from a sidecar proxy")
  public ResponseEntity<Map<String, String>> report(@RequestBody MetricsReport report) {
    metricsStore.ingest(report);
    return ResponseEntity.ok(Map.of("status", "accepted"));
  }
}
