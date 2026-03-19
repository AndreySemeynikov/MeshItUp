package com.mesh.controlplane.canary;

import com.mesh.controlplane.config.CanaryProperties;
import com.mesh.controlplane.config.K8sProperties;
import com.mesh.controlplane.model.CanaryStartRequest;
import com.mesh.controlplane.model.CanaryState;
import com.mesh.controlplane.model.CanaryState.Status;
import com.mesh.controlplane.model.Destination;
import com.mesh.controlplane.store.ConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class CanaryManager {

  private static final Logger log = LoggerFactory.getLogger(CanaryManager.class);

  private final ConfigStore configStore;
  private final KubernetesDeployer k8sDeployer;
  private final CanaryProperties canaryProperties;
  private final K8sProperties k8sProperties;

  private final CanaryState canaryState = new CanaryState();

  public CanaryManager(
          ConfigStore configStore,
          KubernetesDeployer k8sDeployer,
          CanaryProperties canaryProperties,
          K8sProperties k8sProperties) {
    this.configStore = configStore;
    this.k8sDeployer = k8sDeployer;
    this.canaryProperties = canaryProperties;
    this.k8sProperties = k8sProperties;
  }

  /** Start a canary release. */
  public synchronized CanaryState start(CanaryStartRequest request) {
    if (canaryState.getStatus() == Status.IN_PROGRESS) {
      throw new CanaryConflictException(
              "Canary already in progress for service: " + canaryState.getServiceId());
    }

    String serviceId = request.serviceId();
    if (!configStore.hasService(serviceId)) {
      throw new ServiceNotFoundException("Service not found: " + serviceId);
    }

    int initialWeight =
            request.initialWeight() != null
                    ? request.initialWeight()
                    : canaryProperties.defaultInitialWeight();
    int weightStep =
            request.weightStep() != null ? request.weightStep() : canaryProperties.defaultWeightStep();
    double errorThreshold =
            request.errorThreshold() != null
                    ? request.errorThreshold()
                    : canaryProperties.defaultErrorThreshold();

    // Рассчитываем целевой вес, до которого разгоняется canary перед promote.
    // Одна canary-пода должна нести не больше трафика, чем одна stable-пода.
    int stableReplicas = k8sDeployer.getStableReplicas(serviceId);
    int targetWeight = calculateTargetWeight(stableReplicas, initialWeight);

    log.info(
            "Canary sizing for {}: stable has {} replicas → target weight {}% (initial {}%, step {}%)",
            serviceId, stableReplicas, targetWeight, initialWeight, weightStep);

    // Initialize canary state
    canaryState.setServiceId(serviceId);
    canaryState.setStatus(Status.IN_PROGRESS);
    canaryState.setCanaryImage(request.canaryImage());
    canaryState.setCanaryEnv(request.canaryEnv());
    canaryState.setCanaryVersion(
            request.canaryEnv() != null
                    ? request.canaryEnv().getOrDefault("VERSION", "canary")
                    : "canary");
    canaryState.setStableVersion("stable");
    canaryState.setCurrentWeight(initialWeight);
    canaryState.setWeightStep(weightStep);
    canaryState.setTargetWeight(targetWeight);
    canaryState.setStableReplicasAtStart(stableReplicas);
    canaryState.setErrorThreshold(errorThreshold);
    canaryState.setConsecutiveSuccessCount(0);
    canaryState.setStartedAt(Instant.now());
    canaryState.setLastEvaluationAt(null);
    canaryState.setLastEvaluationResult(null);

    try {
      // Create K8s resources
      k8sDeployer.createCanaryDeployment(serviceId, request.canaryImage(), request.canaryEnv());
      k8sDeployer.createCanaryService(serviceId);

      // Wait for canary pod to be ready
      boolean ready =
              k8sDeployer.waitForCanaryReady(serviceId, canaryProperties.readyTimeoutSeconds());
      if (!ready) {
        throw new RuntimeException(
                "Canary pod for " + serviceId + " did not become ready within "
                        + canaryProperties.readyTimeoutSeconds() + "s");
      }

      // Update routes: add canary destination
      String namespace = k8sProperties.namespace();
      String canaryHost = serviceId + "-canary." + namespace + ".svc.cluster.local";
      Destination canaryDest =
              new Destination(serviceId, canaryHost, 8080, "canary", initialWeight);

      configStore.addCanaryDestination(serviceId, canaryDest, 100 - initialWeight);

      log.info(
              "Canary started for {}: image={}, initialWeight={}%, targetWeight={}%",
              serviceId, request.canaryImage(), initialWeight, targetWeight);

      return canaryState;

    } catch (Exception e) {
      log.error("Failed to start canary for {}: {}", serviceId, e.getMessage(), e);
      // Rollback any partially created resources
      try {
        k8sDeployer.deleteCanaryDeployment(serviceId);
        k8sDeployer.deleteCanaryService(serviceId);
      } catch (Exception cleanup) {
        log.warn("Cleanup failed during canary start rollback: {}", cleanup.getMessage());
      }
      canaryState.reset();
      throw new RuntimeException("Failed to start canary: " + e.getMessage(), e);
    }
  }

  /** Promote canary to stable. */
  public synchronized CanaryState promote() {
    if (canaryState.getStatus() != Status.IN_PROGRESS) {
      throw new CanaryConflictException(
              "No active canary to promote. Current status: " + canaryState.getStatus());
    }

    String serviceId = canaryState.getServiceId();
    log.info("Promoting canary for {}: {} → stable", serviceId, canaryState.getCanaryVersion());

    try {
      // Patch stable deployment with canary image
      k8sDeployer.patchStableDeployment(
              serviceId, canaryState.getCanaryImage(), canaryState.getCanaryEnv());

      // Delete canary resources
      k8sDeployer.deleteCanaryDeployment(serviceId);
      k8sDeployer.deleteCanaryService(serviceId);

      // Restore routes: stable=100%, remove canary
      configStore.removeCanaryDestination(serviceId);

      canaryState.setStatus(Status.PROMOTED);
      log.info("Canary promoted: {} → {}", serviceId, canaryState.getCanaryVersion());

      return canaryState;

    } catch (Exception e) {
      log.error("Failed to promote canary for {}: {}", serviceId, e.getMessage(), e);
      throw new RuntimeException("Failed to promote canary: " + e.getMessage(), e);
    }
  }

  /** Rollback canary — delete canary resources and restore stable=100%. */
  public synchronized CanaryState rollback() {
    if (canaryState.getStatus() != Status.IN_PROGRESS) {
      throw new CanaryConflictException(
              "No active canary to rollback. Current status: " + canaryState.getStatus());
    }

    String serviceId = canaryState.getServiceId();
    log.warn("Rolling back canary for {}", serviceId);

    try {
      k8sDeployer.deleteCanaryDeployment(serviceId);
      k8sDeployer.deleteCanaryService(serviceId);
    } catch (Exception e) {
      log.warn("Error during canary rollback cleanup: {}", e.getMessage());
    }

    configStore.removeCanaryDestination(serviceId);
    canaryState.setStatus(Status.ROLLED_BACK);

    log.info("Canary rolled back: {}", serviceId);
    return canaryState;
  }

  /** Increase canary weight. Called by CanaryEvaluator on successful evaluations. */
  public synchronized void increaseWeight() {
    int oldWeight = canaryState.getCurrentWeight();
    int newWeight = oldWeight + canaryState.getWeightStep();
    int targetWeight = canaryState.getTargetWeight();

    if (newWeight >= targetWeight) {
      log.info(
              "Canary weight reached target {}% for {} (would be {}%). Promoting.",
              targetWeight, canaryState.getServiceId(), newWeight);
      promote();
      return;
    }

    canaryState.setCurrentWeight(newWeight);
    configStore.updateRouteWeights(canaryState.getServiceId(), 100 - newWeight, newWeight);
    log.info(
            "Canary weight increased: {} {}% → {}% (target {}%)",
            canaryState.getServiceId(), oldWeight, newWeight, targetWeight);
  }

  /** Get the current canary state. */
  public CanaryState getState() {
    return canaryState;
  }

  // --- Private helpers ---

  /**
   * Рассчитывает целевой вес, до которого разгоняется canary перед promote.
   *
   * <p>Логика: одна canary-пода должна обрабатывать не больше трафика, чем одна stable-пода.
   * Если stable-подов N, то каждая несёт ~100/N процентов трафика. До этого же значения и
   * разгоняем canary.
   *
   * <p>Примеры:
   * <ul>
   *   <li>stable = 1  → target = 100% (canary заменит единственную поду)
   *   <li>stable = 2  → target = 50%
   *   <li>stable = 5  → target = 20%
   *   <li>stable = 10 → target = 10%
   * </ul>
   *
   * <p>Если initialWeight >= расчётного target (например, юзер запустил canary с initialWeight=50
   * при stable=10), возвращаем initialWeight — чтобы первый же успешный evaluation привёл к
   * promote.
   */
  private int calculateTargetWeight(int stableReplicas, int initialWeight) {
    if (stableReplicas <= 0) {
      return 100;
    }
    int target = 100 / stableReplicas;
    return Math.max(initialWeight, Math.min(100, target));
  }

  // --- Custom exceptions ---

  public static class CanaryConflictException extends RuntimeException {
    public CanaryConflictException(String message) {
      super(message);
    }
  }

  public static class ServiceNotFoundException extends RuntimeException {
    public ServiceNotFoundException(String message) {
      super(message);
    }
  }
}