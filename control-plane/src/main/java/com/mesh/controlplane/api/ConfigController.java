package com.mesh.controlplane.api;

import com.mesh.controlplane.config.MeshProperties;
import com.mesh.controlplane.loader.ConfigFileLoader;
import com.mesh.controlplane.model.MeshConfig;
import com.mesh.controlplane.store.ConfigStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/config")
@Tag(name = "Config", description = "Mesh configuration API")
public class ConfigController {

  private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

  private final ConfigStore configStore;
  private final ConfigFileLoader configFileLoader;
  private final MeshProperties meshProperties;

  public ConfigController(
      ConfigStore configStore, ConfigFileLoader configFileLoader, MeshProperties meshProperties) {
    this.configStore = configStore;
    this.configFileLoader = configFileLoader;
    this.meshProperties = meshProperties;
  }

  @GetMapping
  @Operation(
      summary = "Get configuration for a sidecar proxy",
      description = "Returns routes filtered by serviceId and the global retry policy")
  public ResponseEntity<?> getConfig(@RequestParam String serviceId) {
    MeshConfig config = configStore.getConfigForService(serviceId);
    if (config == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(config);
  }

  @PostMapping("/reload")
  @Operation(
      summary = "Reload mesh configuration from file",
      description = "Re-reads mesh-config.yaml and updates the in-memory config store")
  public ResponseEntity<?> reload() {
    try {
      ConfigFileLoader.ParsedConfig parsed = configFileLoader.load(meshProperties.configPath());
      configStore.loadFromFile(parsed.services(), parsed.routes(), parsed.retryPolicy());

      MeshConfig config = configStore.getFullConfig();
      log.info(
          "Config reloaded. Version: {}, services: {}, routes: {}",
          config.version(),
          config.services().size(),
          config.routes().size());

      return ResponseEntity.ok(
          Map.of(
              "status", "reloaded",
              "version", config.version(),
              "servicesCount", config.services().size(),
              "routesCount", config.routes().size()));
    } catch (Exception e) {
      log.error("Config reload failed: {}", e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body(Map.of("status", "error", "message", e.getMessage()));
    }
  }
}
