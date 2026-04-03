package com.mesh.sidecar.metrics;

import com.mesh.sidecar.config.MeshProperties;
import com.mesh.sidecar.model.MetricsReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Модуль 4: MetricsCollector
 *
 * Собирает метрики каждого проксированного запроса — как inbound, так и outbound.
 * Часть A: Prometheus-метрики через Micrometer (кумулятивные, не сбрасываются).
 * Часть B: Агрегированные отчёты для control plane (сбрасываются каждый период).
 */
@Component
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final MeshProperties properties;
    private final MeterRegistry meterRegistry;
    private final RestClient restClient;

    // Ключ: "{direction}:{destination}:{version}"
    private final ConcurrentHashMap<String, RequestStats> statsMap = new ConcurrentHashMap<>();

    public MetricsCollector(MeshProperties properties,
                            MeterRegistry meterRegistry,
                            RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.restClient = restClientBuilder.build();
    }

    /**
     * Записывает метрики одного обработанного запроса.
     *
     * @param direction   "inbound" или "outbound"
     * @param destination имя целевого сервиса (e.g. "inventory-service") или свой serviceId для inbound
     * @param version     "stable" или "canary"
     * @param statusCode  HTTP status code ответа
     * @param durationMs  latency в миллисекундах
     * @param retryCount  сколько retry было выполнено (0 если с первого раза)
     */
    public void record(String direction, String destination, String version, int statusCode,
                       long durationMs, int retryCount) {

        // ----- Часть A: Micrometer / Prometheus метрики -----

        Counter.builder("mesh_proxy_requests_total")
                .description("Total number of proxied requests")
                .tag("direction", direction)
                .tag("destination", destination)
                .tag("version", version)
                .tag("status", String.valueOf(statusCode))
                .register(meterRegistry)
                .increment();

        Timer.builder("mesh_proxy_request_duration_ms")
                .description("Request processing time in milliseconds")
                .tag("direction", direction)
                .tag("destination", destination)
                .tag("version", version)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        if (statusCode >= 500) {
            Counter.builder("mesh_proxy_errors_total")
                    .description("Total number of error responses (status >= 500)")
                    .tag("direction", direction)
                    .tag("destination", destination)
                    .tag("version", version)
                    .register(meterRegistry)
                    .increment();
        }

        if (retryCount > 0) {
            Counter.builder("mesh_proxy_retries_total")
                    .description("Total number of retry attempts")
                    .tag("direction", direction)
                    .tag("destination", destination)
                    .tag("version", version)
                    .register(meterRegistry)
                    .increment(retryCount);
        }

        // ----- Часть B: внутренние счётчики для отчётов control plane -----

        String key = direction + ":" + destination + ":" + version;
        RequestStats stats = statsMap.computeIfAbsent(key, k -> new RequestStats());
        stats.count.incrementAndGet();
        stats.totalLatency.addAndGet(durationMs);
        if (statusCode >= 500) {
            stats.errors.incrementAndGet();
        }
    }

    /**
     * Scheduled отправка агрегированных метрик в control plane.
     */
    @Scheduled(fixedDelayString = "#{meshProperties.metricsReportInterval * 1000}")
    public void sendReport() {
        if (statsMap.isEmpty()) {
            return;
        }

        List<MetricsReport.MetricsEntry> entries = new ArrayList<>();

        for (Map.Entry<String, RequestStats> mapEntry : statsMap.entrySet()) {
            RequestStats stats = mapEntry.getValue();

            int count = stats.count.getAndSet(0);
            int errors = stats.errors.getAndSet(0);
            long totalLatency = stats.totalLatency.getAndSet(0);

            if (count == 0) continue;

            // key format: "direction:destination:version"
            String[] parts = mapEntry.getKey().split(":", 3);
            String direction = parts[0];
            String destination = parts.length > 1 ? parts[1] : "unknown";
            String version = parts.length > 2 ? parts[2] : "unknown";
            long avgLatency = totalLatency / count;

            entries.add(new MetricsReport.MetricsEntry(
                    destination, version, direction, count, errors, avgLatency
            ));
        }

        if (entries.isEmpty()) return;

        MetricsReport report = new MetricsReport(
                properties.getServiceId() + "-sidecar",
                properties.getServiceId(),
                Instant.now(),
                properties.getMetricsReportInterval(),
                entries
        );

        try {
            String reportUrl = properties.getControlPlaneUrl() + "/api/v1/metrics/report";
            restClient.post()
                    .uri(reportUrl)
                    .body(report)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Metrics report sent to control plane: {} entries", entries.size());
        } catch (RestClientException e) {
            log.warn("Failed to send metrics report to control plane: {}", e.getMessage());
        }
    }

    private static class RequestStats {
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicInteger errors = new AtomicInteger(0);
        final AtomicLong totalLatency = new AtomicLong(0);
    }
}
