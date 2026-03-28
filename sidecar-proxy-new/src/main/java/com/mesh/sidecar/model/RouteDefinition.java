package com.mesh.sidecar.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Одно правило маршрутизации: path pattern → список destinations с весами.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteDefinition(
        String source,                      // кто отправляет (e.g. "api-gateway")
        String pathPattern,                 // AntPath паттерн (e.g. "/api/inventory/**")
        List<Destination> destinations      // куда можно отправить с весами
) {}
