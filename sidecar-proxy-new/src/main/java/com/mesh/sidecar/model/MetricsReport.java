package com.mesh.sidecar.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Агрегированный отчёт метрик для control plane.
 * Отправляется через POST /api/v1/metrics/report каждые METRICS_REPORT_INTERVAL секунд.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetricsReport(
        String proxyId,             // идентификатор прокси (e.g. "api-gateway-sidecar")
        String serviceId,           // идентификатор сервиса (e.g. "api-gateway")
        Instant timestamp,          // время отправки отчёта
        int windowSeconds,          // период агрегации в секундах
        List<MetricsEntry> entries  // записи по каждой паре (destination, version, direction)
) {

    /**
     * Одна запись в отчёте — статистика для конкретной комбинации (destination, version, direction).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetricsEntry(
            String destination,   // имя целевого сервиса (e.g. "inventory-service")
            String version,       // "stable" или "canary"
            String direction,     // "inbound" или "outbound"
            int requestCount,     // количество запросов за период
            int errorCount,       // количество ошибок (status >= 500)
            long avgLatencyMs     // среднее время ответа в мс
    ) {}
}
