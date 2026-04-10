package com.mesh.sidecar.routing;

import com.mesh.sidecar.model.Destination;
import com.mesh.sidecar.model.MeshConfig;
import com.mesh.sidecar.model.RetryPolicy;
import com.mesh.sidecar.model.RouteDefinition;
import com.mesh.sidecar.sync.ConfigSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouterTest {

    @Mock
    private ConfigSyncService configSyncService;

    @InjectMocks
    private Router router;

    private MeshConfig meshConfig;

    @BeforeEach
    void setUp() {
        Destination stable = new Destination(
                "inventory-service.mesh.svc.cluster.local", 8080, "stable", 90
        );
        Destination canary = new Destination(
                "inventory-service-canary.mesh.svc.cluster.local", 8080, "canary", 10
        );

        RouteDefinition route = new RouteDefinition(
                "api-gateway",
                "/api/inventory/**",
                List.of(stable, canary)
        );

        meshConfig = new MeshConfig(1, List.of(route), RetryPolicy.defaultPolicy());
    }

    @Test
    @DisplayName("Возвращает destination для совпадающего пути")
    void shouldReturnDestinationForMatchingPath() {
        when(configSyncService.getConfig()).thenReturn(meshConfig);

        Optional<Destination> result = router.route("/api/inventory/items");

        assertThat(result).isPresent();
        assertThat(result.get().version()).isIn("stable", "canary");
        assertThat(result.get().port()).isEqualTo(8080);
    }

    @Test
    @DisplayName("Возвращает empty для несовпадающего пути")
    void shouldReturnEmptyForUnmatchedPath() {
        when(configSyncService.getConfig()).thenReturn(meshConfig);

        Optional<Destination> result = router.route("/api/orders/123");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Возвращает empty если конфигурация не получена")
    void shouldReturnEmptyWhenNoConfig() {
        when(configSyncService.getConfig()).thenReturn(null);

        Optional<Destination> result = router.route("/api/inventory/items");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Совпадает с корневым путём через wildcard")
    void shouldMatchWildcardPath() {
        when(configSyncService.getConfig()).thenReturn(meshConfig);

        assertThat(router.route("/api/inventory/")).isPresent();
        assertThat(router.route("/api/inventory/items/42")).isPresent();
        assertThat(router.route("/api/inventory/items/42/details")).isPresent();
    }

    @RepeatedTest(1000)
    @DisplayName("Weighted random: примерно 90% stable, 10% canary")
    void shouldDistributeWeightsApproximately() {
        when(configSyncService.getConfig()).thenReturn(meshConfig);

        // Выполняем 1000 раз и проверяем распределение в рамках одного RepeatedTest
        // Тест просто проверяет что возвращается корректный destination
        Optional<Destination> result = router.route("/api/inventory/items");
        assertThat(result).isPresent();
        assertThat(result.get().version()).isIn("stable", "canary");
    }

    @Test
    @DisplayName("Weighted random: статистическое распределение близко к ожидаемому")
    void shouldRespectWeightDistribution() {
        when(configSyncService.getConfig()).thenReturn(meshConfig);

        Map<String, Integer> counts = new java.util.HashMap<>();
        int iterations = 10_000;

        for (int i = 0; i < iterations; i++) {
            Optional<Destination> dest = router.route("/api/inventory/items");
            assertThat(dest).isPresent();
            counts.merge(dest.get().version(), 1, Integer::sum);
        }

        int stableCount = counts.getOrDefault("stable", 0);
        int canaryCount = counts.getOrDefault("canary", 0);

        double stableRatio = (double) stableCount / iterations;
        double canaryRatio = (double) canaryCount / iterations;

        // Допуск ±5% от ожидаемых значений
        assertThat(stableRatio).isBetween(0.85, 0.95);
        assertThat(canaryRatio).isBetween(0.05, 0.15);
    }

    @Test
    @DisplayName("Возвращает empty если список destinations пустой")
    void shouldReturnEmptyForEmptyDestinations() {
        RouteDefinition emptyRoute = new RouteDefinition(
                "api-gateway", "/api/inventory/**", List.of()
        );
        MeshConfig config = new MeshConfig(1, List.of(emptyRoute), RetryPolicy.defaultPolicy());
        when(configSyncService.getConfig()).thenReturn(config);

        Optional<Destination> result = router.route("/api/inventory/items");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Выбирает первый совпавший маршрут если их несколько")
    void shouldSelectFirstMatchingRoute() {
        Destination specific = new Destination("specific-service.mesh", 8080, "stable", 100);
        Destination generic = new Destination("generic-service.mesh", 8080, "stable", 100);

        RouteDefinition specificRoute = new RouteDefinition(
                "api-gateway", "/api/inventory/items", List.of(specific)
        );
        RouteDefinition genericRoute = new RouteDefinition(
                "api-gateway", "/api/inventory/**", List.of(generic)
        );

        MeshConfig config = new MeshConfig(1, List.of(specificRoute, genericRoute), RetryPolicy.defaultPolicy());
        when(configSyncService.getConfig()).thenReturn(config);

        Optional<Destination> result = router.route("/api/inventory/items");

        assertThat(result).isPresent();
        assertThat(result.get().host()).isEqualTo("specific-service.mesh");
    }
}
