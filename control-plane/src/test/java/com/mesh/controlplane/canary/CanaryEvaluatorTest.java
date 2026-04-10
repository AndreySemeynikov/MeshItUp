package com.mesh.controlplane.canary;

import com.mesh.controlplane.config.CanaryProperties;
import com.mesh.controlplane.config.K8sProperties;
import com.mesh.controlplane.model.*;
import com.mesh.controlplane.store.ConfigStore;
import com.mesh.controlplane.store.MetricsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CanaryEvaluatorTest {

    @Mock
    private KubernetesDeployer k8sDeployer;

    private ConfigStore configStore;
    private MetricsStore metricsStore;
    private CanaryManager canaryManager;
    private CanaryEvaluator evaluator;
    private CanaryProperties canaryProperties;

    @BeforeEach
    void setUp() {
        configStore = new ConfigStore();
        metricsStore = new MetricsStore();
        canaryProperties = new CanaryProperties(30, 10, 10, 5.0, 3, 2);
        K8sProperties k8sProperties = new K8sProperties("mesh");

        canaryManager = new CanaryManager(configStore, k8sDeployer, canaryProperties, k8sProperties);
        evaluator = new CanaryEvaluator(canaryManager, metricsStore, canaryProperties);

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
    void shouldSkipWhenNoActiveCanary() {
        // No canary started — evaluator should just return silently
        evaluator.evaluate();
        assertEquals(CanaryState.Status.IDLE, canaryManager.getState().getStatus());
    }

    @Test
    void shouldSkipWhenInsufficientData() {
        // Start canary manually by manipulating state
        startCanary();

        // No metrics ingested — should skip
        evaluator.evaluate();

        CanaryState state = canaryManager.getState();
        assertEquals(CanaryState.Status.IN_PROGRESS, state.getStatus());
        assertNotNull(state.getLastEvaluationResult());
        assertTrue(state.getLastEvaluationResult().contains("SKIP"));
    }

    @Test
    void shouldIncrementSuccessCount() {
        startCanary();

        // Ingest good metrics
        ingestMetrics(100, 1, 50, 2); // 2% canary error rate

        evaluator.evaluate();

        CanaryState state = canaryManager.getState();
        assertEquals(CanaryState.Status.IN_PROGRESS, state.getStatus());
        assertEquals(1, state.getConsecutiveSuccessCount());
    }

    @Test
    void shouldRollbackOnHighErrorRate() {
        startCanary();

        // Ingest bad metrics — canary error rate 20%
        ingestMetrics(100, 1, 50, 10);

        evaluator.evaluate();

        CanaryState state = canaryManager.getState();
        assertEquals(CanaryState.Status.ROLLED_BACK, state.getStatus());
    }

    private void startCanary() {
        // Mock K8s deployer to do nothing
        when(k8sDeployer.waitForCanaryReady(anyString(), anyInt())).thenReturn(true);

        CanaryStartRequest request = new CanaryStartRequest(
                "inventory-service",
                "inventory-service:v2",
                Map.of("VERSION", "v2"),
                10, 10, 5.0
        );
        canaryManager.start(request);
    }

    private void ingestMetrics(int stableRequests, int stableErrors,
                               int canaryRequests, int canaryErrors) {
        MetricsReport report = new MetricsReport(
                "api-gateway-sidecar",
                "api-gateway",
                Instant.now(),
                30,
                List.of(
                        new MetricsEntry("inventory-service", "stable", stableRequests, stableErrors, 10),
                        new MetricsEntry("inventory-service", "canary", canaryRequests, canaryErrors, 15)
                )
        );
        metricsStore.ingest(report);
    }
}
