package com.mesh.controlplane.canary;

import com.mesh.controlplane.config.CanaryProperties;
import com.mesh.controlplane.config.K8sProperties;
import com.mesh.controlplane.model.*;
import com.mesh.controlplane.store.ConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CanaryManagerTest {

    @Mock
    private KubernetesDeployer k8sDeployer;

    private ConfigStore configStore;
    private CanaryManager canaryManager;

    @BeforeEach
    void setUp() {
        configStore = new ConfigStore();
        CanaryProperties canaryProperties = new CanaryProperties(30, 10, 10, 5.0, 3, 2);
        K8sProperties k8sProperties = new K8sProperties("mesh");
        canaryManager = new CanaryManager(configStore, k8sDeployer, canaryProperties, k8sProperties);

        // Load base config
        List<ServiceDefinition> services = List.of(
                new ServiceDefinition("api-gateway", "api-gateway.mesh.svc.cluster.local", 8080, "/health"),
                new ServiceDefinition("inventory-service", "inventory-service.mesh.svc.cluster.local", 8080, "/health")
        );
        List<Destination> destinations = new ArrayList<>();
        destinations.add(new Destination("inventory-service", "inventory-service.mesh.svc.cluster.local", 8080, "stable", 100));
        List<RouteDefinition> routes = List.of(
                new RouteDefinition("api-gateway", "/api/inventory/**", destinations)
        );
        configStore.loadFromFile(services, routes, new RetryPolicy(3, 500, List.of(502, 503, 504)));
    }

    @Test
    void shouldStartCanary() {
        when(k8sDeployer.waitForCanaryReady(anyString(), anyInt())).thenReturn(true);

        CanaryStartRequest request = new CanaryStartRequest(
                "inventory-service", "inventory-service:v2",
                Map.of("VERSION", "v2"), 10, 10, 5.0);

        CanaryState state = canaryManager.start(request);

        assertEquals(CanaryState.Status.IN_PROGRESS, state.getStatus());
        assertEquals("inventory-service", state.getServiceId());
        assertEquals(10, state.getCurrentWeight());
        assertNotNull(state.getStartedAt());

        verify(k8sDeployer).createCanaryDeployment(eq("inventory-service"), eq("inventory-service:v2"), any());
        verify(k8sDeployer).createCanaryService("inventory-service");

        // Verify routes updated
        MeshConfig config = configStore.getConfigForService("api-gateway");
        RouteDefinition route = config.routes().get(0);
        assertEquals(2, route.destinations().size());
    }

    @Test
    void shouldRejectDuplicateCanary() {
        when(k8sDeployer.waitForCanaryReady(anyString(), anyInt())).thenReturn(true);

        CanaryStartRequest request = new CanaryStartRequest(
                "inventory-service", "inventory-service:v2",
                Map.of("VERSION", "v2"), 10, 10, 5.0);

        canaryManager.start(request);

        assertThrows(CanaryManager.CanaryConflictException.class, () ->
                canaryManager.start(request));
    }

    @Test
    void shouldRejectUnknownService() {
        CanaryStartRequest request = new CanaryStartRequest(
                "unknown-service", "unknown:v2",
                Map.of(), 10, 10, 5.0);

        assertThrows(CanaryManager.ServiceNotFoundException.class, () ->
                canaryManager.start(request));
    }

    @Test
    void shouldRollback() {
        when(k8sDeployer.waitForCanaryReady(anyString(), anyInt())).thenReturn(true);

        CanaryStartRequest request = new CanaryStartRequest(
                "inventory-service", "inventory-service:v2",
                Map.of("VERSION", "v2"), 10, 10, 5.0);
        canaryManager.start(request);

        CanaryState state = canaryManager.rollback();

        assertEquals(CanaryState.Status.ROLLED_BACK, state.getStatus());
        verify(k8sDeployer).deleteCanaryDeployment("inventory-service");
        verify(k8sDeployer).deleteCanaryService("inventory-service");

        // Verify routes restored
        MeshConfig config = configStore.getConfigForService("api-gateway");
        RouteDefinition route = config.routes().get(0);
        assertEquals(1, route.destinations().size());
        assertEquals(100, route.destinations().get(0).weight());
    }

    @Test
    void shouldIncreaseWeight() {
        when(k8sDeployer.waitForCanaryReady(anyString(), anyInt())).thenReturn(true);

        CanaryStartRequest request = new CanaryStartRequest(
                "inventory-service", "inventory-service:v2",
                Map.of("VERSION", "v2"), 10, 10, 5.0);
        canaryManager.start(request);

        canaryManager.increaseWeight();

        assertEquals(20, canaryManager.getState().getCurrentWeight());

        MeshConfig config = configStore.getConfigForService("api-gateway");
        RouteDefinition route = config.routes().get(0);
        Destination stable = route.destinations().stream()
                .filter(d -> "stable".equals(d.version())).findFirst().orElseThrow();
        Destination canary = route.destinations().stream()
                .filter(d -> "canary".equals(d.version())).findFirst().orElseThrow();

        assertEquals(80, stable.weight());
        assertEquals(20, canary.weight());
    }

    @Test
    void shouldPromoteWhenWeightReaches100() {
        when(k8sDeployer.waitForCanaryReady(anyString(), anyInt())).thenReturn(true);

        CanaryStartRequest request = new CanaryStartRequest(
                "inventory-service", "inventory-service:v2",
                Map.of("VERSION", "v2"), 90, 10, 5.0);
        canaryManager.start(request);

        // Weight 90 + step 10 = 100 → auto-promote
        canaryManager.increaseWeight();

        assertEquals(CanaryState.Status.PROMOTED, canaryManager.getState().getStatus());
        verify(k8sDeployer).patchStableDeployment(eq("inventory-service"), eq("inventory-service:v2"), any());
    }

    @Test
    void shouldUseDefaultsFromProperties() {
        when(k8sDeployer.waitForCanaryReady(anyString(), anyInt())).thenReturn(true);

        // Pass nulls for optional params — should use defaults from properties
        CanaryStartRequest request = new CanaryStartRequest(
                "inventory-service", "inventory-service:v2",
                Map.of("VERSION", "v2"), null, null, null);

        CanaryState state = canaryManager.start(request);

        assertEquals(10, state.getCurrentWeight());
        assertEquals(10, state.getWeightStep());
        assertEquals(5.0, state.getErrorThreshold());
    }
}
