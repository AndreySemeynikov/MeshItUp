package com.mesh.controlplane.store;

import com.mesh.controlplane.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConfigStore {

  private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

  private volatile MeshConfig currentConfig;
  private final AtomicInteger versionCounter = new AtomicInteger(0);

  public synchronized void loadFromFile(
          List<ServiceDefinition> services, List<RouteDefinition> routes, RetryPolicy retryPolicy) {
    int version = versionCounter.incrementAndGet();
    this.currentConfig = new MeshConfig(version, services, routes, retryPolicy);
    log.info("Config loaded. Version: {}, services: {}, routes: {}",
            version, services.size(), routes.size());
  }

  public MeshConfig getFullConfig() {
    return currentConfig;
  }

  public MeshConfig getConfigForService(String serviceId) {
    MeshConfig config = currentConfig;
    if (config == null) {
      return null;
    }

    List<RouteDefinition> filteredRoutes =
            config.routes().stream().filter(r -> r.source().equals(serviceId)).toList();

    log.debug("Config requested by serviceId={}. Returning {} routes, version={}",
            serviceId, filteredRoutes.size(), config.version());

    return new MeshConfig(config.version(), null, filteredRoutes, config.retryPolicy());
  }

  public boolean hasService(String serviceId) {
    MeshConfig config = currentConfig;
    if (config == null || config.services() == null) {
      return false;
    }
    return config.services().stream().anyMatch(s -> s.id().equals(serviceId));
  }

  public ServiceDefinition getService(String serviceId) {
    MeshConfig config = currentConfig;
    if (config == null || config.services() == null) {
      return null;
    }
    return config.services().stream()
            .filter(s -> s.id().equals(serviceId))
            .findFirst()
            .orElse(null);
  }

  public synchronized void addCanaryDestination(
          String serviceId, Destination canaryDestination, int stableWeight) {
    MeshConfig config = currentConfig;
    List<RouteDefinition> updatedRoutes = new ArrayList<>();

    for (RouteDefinition route : config.routes()) {
      boolean hasTarget =
              route.destinations().stream().anyMatch(d -> serviceId.equals(d.serviceId()));

      if (hasTarget) {
        List<Destination> newDests = new ArrayList<>();
        for (Destination d : route.destinations()) {
          if (serviceId.equals(d.serviceId()) && "stable".equals(d.version())) {
            newDests.add(
                    new Destination(d.serviceId(), d.host(), d.port(), d.version(), stableWeight));
          } else {
            newDests.add(d);
          }
        }
        newDests.add(canaryDestination);
        updatedRoutes.add(new RouteDefinition(route.source(), route.pathPattern(), newDests));
      } else {
        updatedRoutes.add(route);
      }
    }

    int version = versionCounter.incrementAndGet();
    this.currentConfig =
            new MeshConfig(version, config.services(), updatedRoutes, config.retryPolicy());
    log.info("Canary destination added for {}. Version: {}", serviceId, version);
  }

  public synchronized void updateRouteWeights(
          String serviceId, int stableWeight, int canaryWeight) {
    MeshConfig config = currentConfig;
    List<RouteDefinition> updatedRoutes = new ArrayList<>();

    for (RouteDefinition route : config.routes()) {
      boolean hasTarget =
              route.destinations().stream().anyMatch(d -> serviceId.equals(d.serviceId()));

      if (hasTarget) {
        List<Destination> newDests = new ArrayList<>();
        for (Destination d : route.destinations()) {
          if (serviceId.equals(d.serviceId()) && "stable".equals(d.version())) {
            newDests.add(
                    new Destination(d.serviceId(), d.host(), d.port(), d.version(), stableWeight));
          } else if (serviceId.equals(d.serviceId()) && "canary".equals(d.version())) {
            newDests.add(
                    new Destination(d.serviceId(), d.host(), d.port(), d.version(), canaryWeight));
          } else {
            newDests.add(d);
          }
        }
        updatedRoutes.add(new RouteDefinition(route.source(), route.pathPattern(), newDests));
      } else {
        updatedRoutes.add(route);
      }
    }

    int version = versionCounter.incrementAndGet();
    this.currentConfig =
            new MeshConfig(version, config.services(), updatedRoutes, config.retryPolicy());
    log.info("Route weights updated for {}: stable={}%, canary={}%. Version: {}",
            serviceId, stableWeight, canaryWeight, version);
  }

  public synchronized void removeCanaryDestination(String serviceId) {
    MeshConfig config = currentConfig;
    List<RouteDefinition> updatedRoutes = new ArrayList<>();

    for (RouteDefinition route : config.routes()) {
      boolean hasTarget =
              route.destinations().stream().anyMatch(d -> serviceId.equals(d.serviceId()));

      if (hasTarget) {
        List<Destination> newDests = new ArrayList<>();
        for (Destination d : route.destinations()) {
          if (serviceId.equals(d.serviceId()) && "canary".equals(d.version())) {
            continue;
          }
          if (serviceId.equals(d.serviceId()) && "stable".equals(d.version())) {
            newDests.add(new Destination(d.serviceId(), d.host(), d.port(), d.version(), 100));
          } else {
            newDests.add(d);
          }
        }
        updatedRoutes.add(new RouteDefinition(route.source(), route.pathPattern(), newDests));
      } else {
        updatedRoutes.add(route);
      }
    }

    int version = versionCounter.incrementAndGet();
    this.currentConfig =
            new MeshConfig(version, config.services(), updatedRoutes, config.retryPolicy());
    log.info("Canary destination removed for {}. Stable restored to 100%. Version: {}",
            serviceId, version);
  }
}
