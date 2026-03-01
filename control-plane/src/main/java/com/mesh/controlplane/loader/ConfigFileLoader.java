package com.mesh.controlplane.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mesh.controlplane.model.Destination;
import com.mesh.controlplane.model.RetryPolicy;
import com.mesh.controlplane.model.RouteDefinition;
import com.mesh.controlplane.model.ServiceDefinition;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConfigFileLoader {

  private static final Logger log = LoggerFactory.getLogger(ConfigFileLoader.class);

  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  public record ParsedConfig(
      List<ServiceDefinition> services, List<RouteDefinition> routes, RetryPolicy retryPolicy) {}

  /**
   * Read and parse mesh-config.yaml from the given path. Validates referential integrity and weight
   * sums.
   *
   * @throws IllegalStateException if file is not found or invalid
   */
  public ParsedConfig load(String path) {
    File file = new File(path);
    if (!file.exists()) {
      throw new IllegalStateException("Config file not found: " + path);
    }

    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> raw = yamlMapper.readValue(file, Map.class);

      List<ServiceDefinition> services = parseServices(raw);
      List<RouteDefinition> routes = parseRoutes(raw);
      RetryPolicy retryPolicy = parseRetryPolicy(raw);

      validate(services, routes);

      log.info(
          "Config file parsed successfully: {} services, {} routes from {}",
          services.size(),
          routes.size(),
          path);

      return new ParsedConfig(services, routes, retryPolicy);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to parse config file: " + path, e);
    }
  }

  @SuppressWarnings("unchecked")
  private List<ServiceDefinition> parseServices(Map<String, Object> raw) {
    List<Map<String, Object>> servicesList = (List<Map<String, Object>>) raw.get("services");
    if (servicesList == null) {
      throw new IllegalStateException("Config file missing 'services' section");
    }
    return servicesList.stream()
        .map(
            m ->
                new ServiceDefinition(
                    (String) m.get("id"),
                    (String) m.get("host"),
                    (Integer) m.get("port"),
                    (String) m.get("healthPath")))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private List<RouteDefinition> parseRoutes(Map<String, Object> raw) {
    List<Map<String, Object>> routesList = (List<Map<String, Object>>) raw.get("routes");
    if (routesList == null) {
      throw new IllegalStateException("Config file missing 'routes' section");
    }
    return routesList.stream()
        .map(
            m -> {
              List<Map<String, Object>> destList =
                  (List<Map<String, Object>>) m.get("destinations");
              List<Destination> destinations =
                  destList.stream()
                      .map(
                          d ->
                              new Destination(
                                  (String) d.get("serviceId"),
                                  (String) d.get("host"),
                                  (Integer) d.get("port"),
                                  (String) d.get("version"),
                                  (Integer) d.get("weight")))
                      .toList();
              return new RouteDefinition(
                  (String) m.get("source"), (String) m.get("pathPattern"), destinations);
            })
        .toList();
  }

  @SuppressWarnings("unchecked")
  private RetryPolicy parseRetryPolicy(Map<String, Object> raw) {
    Map<String, Object> retry = (Map<String, Object>) raw.get("retryPolicy");
    if (retry == null) {
      // Default retry policy
      return new RetryPolicy(3, 500, List.of(502, 503, 504));
    }
    List<Integer> codes = (List<Integer>) retry.get("retriableStatusCodes");
    return new RetryPolicy(
        (Integer) retry.get("maxAttempts"),
        ((Number) retry.get("delayMs")).longValue(),
        codes != null ? codes : List.of(502, 503, 504));
  }

  /**
   * Validate referential integrity: - All serviceId in route destinations must reference existing
   * services - Weights in each route must sum to 100
   */
  private void validate(List<ServiceDefinition> services, List<RouteDefinition> routes) {
    Set<String> serviceIds =
            services.stream().map(ServiceDefinition::id).collect(Collectors.toSet());

    for (RouteDefinition route : routes) {
      int weightSum = 0;
      for (Destination dest : route.destinations()) {
        // serviceId опциональный — localhost destinations его не имеют
        if (dest.serviceId() != null && !serviceIds.contains(dest.serviceId())) {
          throw new IllegalStateException(
                  "Route '%s' references unknown serviceId: '%s'"
                          .formatted(route.pathPattern(), dest.serviceId()));
        }
        weightSum += dest.weight();
      }
      if (weightSum != 100) {
        throw new IllegalStateException(
                "Route '%s' destination weights sum to %d, expected 100"
                        .formatted(route.pathPattern(), weightSum));
      }
    }
  }
}
