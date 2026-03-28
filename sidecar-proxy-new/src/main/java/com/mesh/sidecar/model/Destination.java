package com.mesh.sidecar.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Один destination (куда отправить запрос) с весом для weighted random.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Destination(
        String host,       // K8s DNS имя (e.g. "inventory-service.mesh.svc.cluster.local")
        int port,          // порт сервиса (e.g. 8080)
        String version,    // метка версии ("stable" или "canary")
        int weight         // вес от 0 до 100
) {}
