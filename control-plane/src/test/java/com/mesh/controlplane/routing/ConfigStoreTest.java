package com.mesh.controlplane.routing;

import com.mesh.controlplane.model.*;
import com.mesh.controlplane.store.ConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {

    private ConfigStore configStore;

    @BeforeEach
    void setUp() {
        configStore = new ConfigStore();

        List<ServiceDefinition> services = List.of(
                new ServiceDefinition("api-gateway", "api-gateway.mesh.svc.cluster.local", 8080, "/health"),
                new ServiceDefinition("inventory-service", "inventory-service.mesh.svc.cluster.local", 8080, "/health")
        );

        List<Destination> destinations = new ArrayList<>();
        destinations.add(new Destination("inventory-service", "inventory-service.mesh.svc.cluster.local", 8080, "stable", 100));

        List<RouteDefinition> routes = List.of(
                new RouteDefinition("api-gateway", "/api/inventory/**", destinations)
        );

        RetryPolicy retryPolicy = new RetryPolicy(3, 500, List.of(502, 503, 504));

        configStore.loadFromFile(services, routes, retryPolicy);
    }

    @Test
    void shouldLoadConfig() {
        MeshConfig config = configStore.getFullConfig();
        assertNotNull(config);
        assertEquals(1, config.version());
        assertEquals(2, config.services().size());
        assertEquals(1, config.routes().size());
    }

    @Test
    void shouldFilterByServiceId() {
        MeshConfig config = configStore.getConfigForService("api-gateway");
        assertNotNull(config);
        assertEquals(1, config.routes().size());
        assertEquals("api-gateway", config.routes().get(0).source());
        assertNull(config.services()); // services not returned for sidecar
    }

    @Test
    void shouldReturnEmptyRoutesForUnknownService() {
        MeshConfig config = configStore.getConfigForService("unknown-service");
        assertNotNull(config);
        assertTrue(config.routes().isEmpty());
    }

    @Test
    void shouldCheckServiceExists() {
        assertTrue(configStore.hasService("api-gateway"));
        assertTrue(configStore.hasService("inventory-service"));
        assertFalse(configStore.hasService("unknown"));
    }

    @Test
    void shouldAddCanaryDestination() {
        Destination canary = new Destination(
                "inventory-service",
                "inventory-service-canary.mesh.svc.cluster.local",
                8080, "canary", 10);

        configStore.addCanaryDestination("inventory-service", canary, 90);

        MeshConfig config = configStore.getConfigForService("api-gateway");
        RouteDefinition route = config.routes().get(0);
        assertEquals(2, route.destinations().size());

        Destination stable = route.destinations().stream()
                .filter(d -> "stable".equals(d.version())).findFirst().orElseThrow();
        Destination canaryDest = route.destinations().stream()
                .filter(d -> "canary".equals(d.version())).findFirst().orElseThrow();

        assertEquals(90, stable.weight());
        assertEquals(10, canaryDest.weight());
    }

    @Test
    void shouldUpdateRouteWeights() {
        // First add canary
        Destination canary = new Destination(
                "inventory-service",
                "inventory-service-canary.mesh.svc.cluster.local",
                8080, "canary", 10);
        configStore.addCanaryDestination("inventory-service", canary, 90);

        // Then update weights
        configStore.updateRouteWeights("inventory-service", 70, 30);

        MeshConfig config = configStore.getConfigForService("api-gateway");
        RouteDefinition route = config.routes().get(0);

        Destination stable = route.destinations().stream()
                .filter(d -> "stable".equals(d.version())).findFirst().orElseThrow();
        Destination canaryDest = route.destinations().stream()
                .filter(d -> "canary".equals(d.version())).findFirst().orElseThrow();

        assertEquals(70, stable.weight());
        assertEquals(30, canaryDest.weight());
    }

    @Test
    void shouldRemoveCanaryDestination() {
        // Add canary first
        Destination canary = new Destination(
                "inventory-service",
                "inventory-service-canary.mesh.svc.cluster.local",
                8080, "canary", 10);
        configStore.addCanaryDestination("inventory-service", canary, 90);

        // Remove it
        configStore.removeCanaryDestination("inventory-service");

        MeshConfig config = configStore.getConfigForService("api-gateway");
        RouteDefinition route = config.routes().get(0);
        assertEquals(1, route.destinations().size());
        assertEquals("stable", route.destinations().get(0).version());
        assertEquals(100, route.destinations().get(0).weight());
    }

    @Test
    void shouldIncrementVersionOnEachChange() {
        int v1 = configStore.getFullConfig().version();

        Destination canary = new Destination(
                "inventory-service",
                "inventory-service-canary.mesh.svc.cluster.local",
                8080, "canary", 10);
        configStore.addCanaryDestination("inventory-service", canary, 90);
        int v2 = configStore.getFullConfig().version();

        configStore.updateRouteWeights("inventory-service", 70, 30);
        int v3 = configStore.getFullConfig().version();

        assertTrue(v2 > v1);
        assertTrue(v3 > v2);
    }
}
