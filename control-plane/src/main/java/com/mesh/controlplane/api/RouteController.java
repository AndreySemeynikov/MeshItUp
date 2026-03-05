package com.mesh.controlplane.api;

import com.mesh.controlplane.model.MeshConfig;
import com.mesh.controlplane.model.RouteDefinition;
import com.mesh.controlplane.store.ConfigStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collections;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routes")
@Tag(name = "Routes", description = "Current mesh routes API")
public class RouteController {

  private final ConfigStore configStore;

  public RouteController(ConfigStore configStore) {
    this.configStore = configStore;
  }

  @GetMapping
  @Operation(summary = "List all current routes with actual weights")
  public ResponseEntity<List<RouteDefinition>> getRoutes() {
    MeshConfig config = configStore.getFullConfig();
    if (config == null || config.routes() == null) {
      return ResponseEntity.ok(Collections.emptyList());
    }
    return ResponseEntity.ok(config.routes());
  }
}
