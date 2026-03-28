package com.mesh.sidecar.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Полная конфигурация от control plane.
 * Приходит в ответ на GET /api/v1/config?serviceId={serviceId}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MeshConfig(
        int version,                    // номер версии конфигурации
        List<RouteDefinition> routes,   // список маршрутов для данного сервиса
        RetryPolicy retryPolicy         // глобальная политика retry
) {}
