package com.mesh.controlplane.api;

import com.mesh.controlplane.model.MeshConfig;
import com.mesh.controlplane.model.ServiceDefinition;
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
@RequestMapping("/api/v1/services")
@Tag(name = "Services", description = "Registered services API")
public class ServiceController {

  private final ConfigStore configStore;

  public ServiceController(ConfigStore configStore) {
    this.configStore = configStore;
  }

  @GetMapping
  @Operation(summary = "List all registered services")
  public ResponseEntity<List<ServiceDefinition>> getServices() {
    MeshConfig config = configStore.getFullConfig();
    if (config == null || config.services() == null) {
      return ResponseEntity.ok(Collections.emptyList());
    }
    return ResponseEntity.ok(config.services());
  }
}
