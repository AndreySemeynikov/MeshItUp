package com.mesh.controlplane.loader;

import com.mesh.controlplane.config.MeshProperties;
import com.mesh.controlplane.store.ConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ConfigInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ConfigInitializer.class);

  private final ConfigFileLoader configFileLoader;
  private final ConfigStore configStore;
  private final MeshProperties meshProperties;

  public ConfigInitializer(
      ConfigFileLoader configFileLoader, ConfigStore configStore, MeshProperties meshProperties) {
    this.configFileLoader = configFileLoader;
    this.configStore = configStore;
    this.meshProperties = meshProperties;
  }

  @Override
  public void run(ApplicationArguments args) {
    String path = meshProperties.configPath();
    log.info("Loading mesh config from: {}", path);

    try {
      ConfigFileLoader.ParsedConfig parsed = configFileLoader.load(path);
      configStore.loadFromFile(parsed.services(), parsed.routes(), parsed.retryPolicy());
      log.info(
          "Control plane started. Loaded {} services, {} routes from {}",
          parsed.services().size(),
          parsed.routes().size(),
          path);
    } catch (Exception e) {
      log.error("Failed to load mesh config from {}: {}", path, e.getMessage());
      throw new IllegalStateException("Control plane cannot start with invalid configuration", e);
    }
  }
}
