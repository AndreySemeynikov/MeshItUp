package com.mesh.sidecar.routing;

import com.mesh.sidecar.model.Destination;
import com.mesh.sidecar.model.MeshConfig;
import com.mesh.sidecar.model.RouteDefinition;
import com.mesh.sidecar.sync.ConfigSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Модуль 2: Router
 *
 * Stateless компонент. По входящему HTTP-запросу определяет конкретный destination
 * (host:port + version) с помощью weighted random и AntPathMatcher.
 */
@Component
public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    private final ConfigSyncService configSyncService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public Router(ConfigSyncService configSyncService) {
        this.configSyncService = configSyncService;
    }

    /**
     * Определяет destination для входящего запроса.
     *
     * @param requestPath путь входящего запроса (e.g. "/api/inventory/items")
     * @return выбранный Destination или Optional.empty() если маршрут не найден
     */
    public Optional<Destination> route(String requestPath) {
        MeshConfig config = configSyncService.getConfig();

        if (config == null || config.routes() == null) {
            log.warn("No config available, cannot route request to {}", requestPath);
            return Optional.empty();
        }

        // Ищем первый подходящий маршрут по AntPath паттерну
        for (RouteDefinition route : config.routes()) {
            if (pathMatcher.match(route.pathPattern(), requestPath)) {
                return selectDestination(route.destinations(), requestPath);
            }
        }

        log.warn("No route matched for path: {}", requestPath);
        return Optional.empty();
    }

    /**
     * Weighted random selection среди destinations.
     *
     * Алгоритм:
     *   random = ThreadLocalRandom.current().nextInt(100)  // 0..99
     *   cumulative = 0
     *   for each destination:
     *       cumulative += destination.weight
     *       if random < cumulative: return destination
     *
     * Пример: [stable(90), canary(10)]
     *   random 0-89  → stable
     *   random 90-99 → canary
     */
    private Optional<Destination> selectDestination(List<Destination> destinations, String requestPath) {
        if (destinations == null || destinations.isEmpty()) {
            log.warn("Route matched for {} but has no destinations", requestPath);
            return Optional.empty();
        }

        int random = ThreadLocalRandom.current().nextInt(100);
        int cumulative = 0;

        for (Destination destination : destinations) {
            cumulative += destination.weight();
            if (random < cumulative) {
                return Optional.of(destination);
            }
        }

        // Fallback: если веса не суммируются до 100 — берём последний
        Destination fallback = destinations.get(destinations.size() - 1);
        log.warn("Weights for path {} don't sum to 100, falling back to last destination: {}",
                requestPath, fallback.host());
        return Optional.of(fallback);
    }
}
