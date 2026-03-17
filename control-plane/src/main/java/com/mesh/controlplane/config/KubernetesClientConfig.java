package com.mesh.controlplane.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.ClientBuilder;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration
public class KubernetesClientConfig {

  private static final Logger log = LoggerFactory.getLogger(KubernetesClientConfig.class);

  @Bean
  public ApiClient apiClient() throws IOException {
    ApiClient client;
    try {
      // Inside a Pod — uses ServiceAccount token automatically
      client = ClientBuilder.cluster().build();
    } catch (Exception e) {
      log.warn(
          "Failed to build in-cluster K8s client, falling back to default kubeconfig: {}",
          e.getMessage());
      client = ClientBuilder.defaultClient();
    }
    Configuration.setDefaultApiClient(client);
    return client;
  }

  @Bean
  public AppsV1Api appsV1Api(ApiClient client) {
    return new AppsV1Api(client);
  }

  @Bean
  public CoreV1Api coreV1Api(ApiClient client) {
    return new CoreV1Api(client);
  }
}
