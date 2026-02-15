package com.mesh.controlplane.canary;

import com.mesh.controlplane.config.K8sProperties;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1HTTPGetAction;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Probe;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class KubernetesDeployer {

  private static final Logger log = LoggerFactory.getLogger(KubernetesDeployer.class);

  private static final String CONTROL_PLANE_URL =
      "http://mesh-control-plane.mesh.svc.cluster.local:8080";
  private static final String SIDECAR_IMAGE = "mesh-sidecar:latest";

  private final AppsV1Api appsV1Api;
  private final CoreV1Api coreV1Api;
  private final K8sProperties k8sProperties;

  public KubernetesDeployer(AppsV1Api appsV1Api, CoreV1Api coreV1Api, K8sProperties k8sProperties) {
    this.appsV1Api = appsV1Api;
    this.coreV1Api = coreV1Api;
    this.k8sProperties = k8sProperties;
  }

  /** Create a canary Deployment for the given service. */
  public void createCanaryDeployment(
      String serviceId, String canaryImage, Map<String, String> env) {
    String deploymentName = serviceId + "-canary";
    String namespace = k8sProperties.namespace();

    Map<String, String> labels =
        Map.of(
            "app", serviceId,
            "version", "canary",
            "mesh", "true");

    // Build env vars for the business container
    List<V1EnvVar> envVars = new ArrayList<>();
    if (env != null) {
      env.forEach((key, value) -> envVars.add(new V1EnvVar().name(key).value(value)));
    }

    // Business container
    V1Container businessContainer =
        new V1Container()
            .name(serviceId)
            .image(canaryImage)
            .imagePullPolicy("IfNotPresent") // ← добавить
            .addPortsItem(new V1ContainerPort().containerPort(8080))
            .env(envVars);

    // Sidecar container — MESH_SERVICE_ID = serviceId (NOT serviceId-canary)
    V1Container sidecarContainer =
        new V1Container()
            .name("mesh-sidecar")
            .image(SIDECAR_IMAGE)
            .imagePullPolicy("IfNotPresent") // ← добавить
            .addPortsItem(new V1ContainerPort().containerPort(15001))
            .addPortsItem(new V1ContainerPort().containerPort(15002))
            .addPortsItem(new V1ContainerPort().containerPort(15006)) // ← добавить
            .addEnvItem(new V1EnvVar().name("MESH_SERVICE_ID").value(serviceId))
            .addEnvItem(new V1EnvVar().name("MESH_CONTROL_PLANE_URL").value(CONTROL_PLANE_URL))
                .startupProbe(
                        new V1Probe()
                                .httpGet(new V1HTTPGetAction()
                                        .path("/actuator/health")
                                        .port(new IntOrString(15002)))
                                .initialDelaySeconds(10)
                                .periodSeconds(5)
                                .failureThreshold(30))
                .readinessProbe(
                        new V1Probe()
                                .httpGet(new V1HTTPGetAction()
                                        .path("/actuator/health")
                                        .port(new IntOrString(15002)))
                                .initialDelaySeconds(0)
                                .periodSeconds(5)
                                .failureThreshold(6)
                                .successThreshold(1))
                .livenessProbe(
                        new V1Probe()
                                .httpGet(new V1HTTPGetAction()
                                        .path("/actuator/health")
                                        .port(new IntOrString(15002)))
                                .initialDelaySeconds(0)
                                .periodSeconds(10)
                                .failureThreshold(3))
            .resources(
                new V1ResourceRequirements()
                    .putRequestsItem("cpu", new io.kubernetes.client.custom.Quantity("50m"))
                    .putRequestsItem("memory", new io.kubernetes.client.custom.Quantity("128Mi"))
                    .putLimitsItem("cpu", new io.kubernetes.client.custom.Quantity("300m"))
                    .putLimitsItem("memory", new io.kubernetes.client.custom.Quantity("512Mi")));

    Map<String, String> metadata =
        Map.of( // ← добавить
            "prometheus.io/scrape", "true",
            "prometheus.io/port", "15002",
            "prometheus.io/path", "/actuator/prometheus");
    V1Deployment deployment =
        new V1Deployment()
            .metadata(new V1ObjectMeta().name(deploymentName).namespace(namespace).labels(labels))
            .spec(
                new V1DeploymentSpec()
                    .replicas(1)
                    .selector(new V1LabelSelector().matchLabels(labels))
                    .template(
                        new V1PodTemplateSpec()
                            .metadata(new V1ObjectMeta().labels(labels).annotations(metadata))
                            .spec(
                                new V1PodSpec()
                                    .containers(List.of(businessContainer, sidecarContainer)))));

    try {
      appsV1Api.createNamespacedDeployment(namespace, deployment).execute();
      log.info("Created canary Deployment: {}/{}", namespace, deploymentName);
    } catch (ApiException e) {
      if (e.getCode() == 409) {
        log.warn("Canary Deployment already exists: {}/{}", namespace, deploymentName);
      } else {
        log.error(
            "K8s API error: create Deployment {} → {} {}",
            deploymentName,
            e.getCode(),
            e.getResponseBody());
        throw new RuntimeException("Failed to create canary Deployment: " + e.getMessage(), e);
      }
    }
  }

  /** Create a canary Service for the given service. */
  public void createCanaryService(String serviceId) {
    String serviceName = serviceId + "-canary";
    String namespace = k8sProperties.namespace();

    Map<String, String> selector = Map.of("app", serviceId, "version", "canary");

    V1Service service =
        new V1Service()
            .metadata(
                new V1ObjectMeta()
                    .name(serviceName)
                    .namespace(namespace)
                    .labels(Map.of("app", serviceId, "version", "canary")))
            .spec(
                new V1ServiceSpec()
                    .selector(selector)
                    .addPortsItem(
                        new V1ServicePort()
                            .name("http")
                            .port(8080)
                            .targetPort(new IntOrString(15006)))
                    .type("ClusterIP"));

    try {
      coreV1Api.createNamespacedService(namespace, service).execute();
      log.info("Created canary Service: {}/{}", namespace, serviceName);
    } catch (ApiException e) {
      if (e.getCode() == 409) {
        log.warn("Canary Service already exists: {}/{}", namespace, serviceName);
      } else {
        log.error(
            "K8s API error: create Service {} → {} {}",
            serviceName,
            e.getCode(),
            e.getResponseBody());
        throw new RuntimeException("Failed to create canary Service: " + e.getMessage(), e);
      }
    }
  }

  /** Patch the stable Deployment with a new image and env vars (for promote). */
  public void patchStableDeployment(String serviceId, String newImage, Map<String, String> newEnv) {
    String namespace = k8sProperties.namespace();

    try {
      V1Deployment existing = appsV1Api.readNamespacedDeployment(serviceId, namespace).execute();
      V1Container businessContainer =
          existing.getSpec().getTemplate().getSpec().getContainers().get(0);

      businessContainer.setImage(newImage);
      if (newEnv != null) {
        List<V1EnvVar> envVars = new ArrayList<>();
        newEnv.forEach((key, value) -> envVars.add(new V1EnvVar().name(key).value(value)));
        businessContainer.setEnv(envVars);
      }

      appsV1Api.replaceNamespacedDeployment(serviceId, namespace, existing).execute();
      log.info("Patched stable Deployment: {}/{} → image={}", namespace, serviceId, newImage);
    } catch (ApiException e) {
      log.error(
          "K8s API error: patch Deployment {} → {} {}",
          serviceId,
          e.getCode(),
          e.getResponseBody());
      throw new RuntimeException("Failed to patch stable Deployment: " + e.getMessage(), e);
    }
  }

  /** Delete the canary Deployment. */
  public void deleteCanaryDeployment(String serviceId) {
    String deploymentName = serviceId + "-canary";
    String namespace = k8sProperties.namespace();

    try {
      appsV1Api.deleteNamespacedDeployment(deploymentName, namespace).execute();
      log.info("Deleted canary Deployment: {}/{}", namespace, deploymentName);
    } catch (ApiException e) {
      if (e.getCode() == 404) {
        log.warn(
            "Canary Deployment not found (already deleted?): {}/{}", namespace, deploymentName);
      } else {
        log.error(
            "K8s API error: delete Deployment {} → {} {}",
            deploymentName,
            e.getCode(),
            e.getResponseBody());
        throw new RuntimeException("Failed to delete canary Deployment: " + e.getMessage(), e);
      }
    }
  }

  /** Delete the canary Service. */
  public void deleteCanaryService(String serviceId) {
    String serviceName = serviceId + "-canary";
    String namespace = k8sProperties.namespace();

    try {
      coreV1Api.deleteNamespacedService(serviceName, namespace).execute();
      log.info("Deleted canary Service: {}/{}", namespace, serviceName);
    } catch (ApiException e) {
      if (e.getCode() == 404) {
        log.warn("Canary Service not found (already deleted?): {}/{}", namespace, serviceName);
      } else {
        log.error(
            "K8s API error: delete Service {} → {} {}",
            serviceName,
            e.getCode(),
            e.getResponseBody());
        throw new RuntimeException("Failed to delete canary Service: " + e.getMessage(), e);
      }
    }
  }

  /** Wait for a canary pod to become Ready. */
  public boolean waitForCanaryReady(String serviceId, int timeoutSeconds) {
    String namespace = k8sProperties.namespace();
    String labelSelector = "app=" + serviceId + ",version=canary";
    long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

    log.info("Waiting for canary pod to become ready: {}, timeout={}s", serviceId, timeoutSeconds);

    while (System.currentTimeMillis() < deadline) {
      try {
        V1PodList pods =
            coreV1Api.listNamespacedPod(namespace).labelSelector(labelSelector).execute();

        boolean allReady =
            !pods.getItems().isEmpty()
                && pods.getItems().stream()
                    .allMatch(
                        pod -> {
                          if (pod.getStatus() == null || pod.getStatus().getConditions() == null)
                            return false;
                          return pod.getStatus().getConditions().stream()
                              .anyMatch(
                                  c ->
                                      "Ready".equals(c.getType())
                                          && Boolean.TRUE.equals(
                                              c.getStatus() != null
                                                  && "True".equals(c.getStatus())));
                        });

        if (allReady) {
          log.info("Canary pod is ready: {}", serviceId);
          return true;
        }

        Thread.sleep(2000);
      } catch (ApiException e) {
        log.warn("Error checking pod status: {} {}", e.getCode(), e.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    log.warn("Timeout waiting for canary pod: {}", serviceId);
    return false;
  }


}
