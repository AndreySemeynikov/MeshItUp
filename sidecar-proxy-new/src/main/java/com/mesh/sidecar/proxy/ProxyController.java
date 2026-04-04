package com.mesh.sidecar.proxy;

import com.mesh.sidecar.config.MeshProperties;
import com.mesh.sidecar.forwarding.HttpForwarder;
import com.mesh.sidecar.metrics.MetricsCollector;
import com.mesh.sidecar.model.Destination;
import com.mesh.sidecar.model.ForwardResult;
import com.mesh.sidecar.model.MeshConfig;
import com.mesh.sidecar.model.RetryPolicy;
import com.mesh.sidecar.routing.Router;
import com.mesh.sidecar.sync.ConfigSyncService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * ProxyController — единственный @RestController.
 *
 * Обрабатывает ВСЕ HTTP-запросы на обоих портах:
 *   - Порт 15001 (outbound): запросы от бизнес-сервиса → маршрутизация через Router → другой сервис
 *   - Порт 15006 (inbound):  запросы извне пода → пересылка на localhost:8080 (бизнес-сервис)
 *
 * Режим определяется по request.getLocalPort() — на какой порт пришёл запрос.
 * Это аналог Envoy listeners в Istio (15001 outbound, 15006 inbound).
 */
@RestController
@RequestMapping("/**")
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final MeshProperties properties;
    private final ConfigSyncService configSyncService;
    private final Router router;
    private final HttpForwarder httpForwarder;
    private final MetricsCollector metricsCollector;

    public ProxyController(MeshProperties properties,
                           ConfigSyncService configSyncService,
                           Router router,
                           HttpForwarder httpForwarder,
                           MetricsCollector metricsCollector) {
        this.properties = properties;
        this.configSyncService = configSyncService;
        this.router = router;
        this.httpForwarder = httpForwarder;
        this.metricsCollector = metricsCollector;
    }

    @RequestMapping
    public ResponseEntity<byte[]> handle(HttpServletRequest request) {
        int localPort = request.getLocalPort();

        if (localPort == properties.getInboundPort()) {
            return handleInbound(request);
        } else {
            return handleOutbound(request);
        }
    }

    /**
     * Inbound: запрос пришёл извне пода на порт 15006.
     * Пересылаем на localhost:LOCAL_SERVICE_PORT (бизнес-сервис рядом).
     * Не требует конфигурации от control plane — цель всегда localhost.
     * Записываем inbound-метрики.
     */
    private ResponseEntity<byte[]> handleInbound(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Определяем source (кто вызвал) из mesh-заголовка
        String source = request.getHeader("X-Mesh-Source");
        if (source == null) source = "external";

        String version = request.getHeader("X-Mesh-Route-Version");
        if (version == null) version = "unknown";

        long startTime = System.nanoTime();

        ForwardResult result = httpForwarder.forwardToLocalService(request);

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        log.info("INBOUND {} {} ← {} [{}] → localhost:{} → {} ({}ms)",
                method, path, source, version,
                properties.getLocalServicePort(),
                result.statusCode(), durationMs);

        // Записываем inbound-метрики
        metricsCollector.record(
                "inbound",
                properties.getServiceId(),
                version,
                result.statusCode(),
                durationMs,
                0
        );

        HttpHeaders responseHeaders = result.headers() != null ? result.headers() : new HttpHeaders();

        return ResponseEntity
                .status(result.statusCode())
                .headers(responseHeaders)
                .body(result.body());
    }

    /**
     * Outbound: запрос от бизнес-сервиса на порт 15001.
     * Маршрутизация через Router → пересылка к другому сервису.
     * Это оригинальная логика, без изменений.
     */
    private ResponseEntity<byte[]> handleOutbound(HttpServletRequest request) {
        if (!configSyncService.isConfigured()) {
            log.warn("Received outbound request but sidecar is not configured yet");
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("{\"error\": \"sidecar not configured yet\"}".getBytes());
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        Optional<Destination> destinationOpt = router.route(path);

        if (destinationOpt.isEmpty()) {
            log.warn("No route found for {} {}", method, path);
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(("{\"error\": \"no route found for path: " + path + "\"}").getBytes());
        }

        Destination destination = destinationOpt.get();

        MeshConfig config = configSyncService.getConfig();
        RetryPolicy retryPolicy = config.retryPolicy() != null
                ? config.retryPolicy()
                : RetryPolicy.defaultPolicy();

        ForwardResult result = httpForwarder.forward(request, destination, retryPolicy);

        log.info("OUTBOUND {} {} → {}:{} [{}] → {} ({}ms)",
                method, path,
                destination.host(), destination.port(),
                destination.version(),
                result.statusCode(),
                result.durationMs());

        String serviceName = destination.host().split("\\.")[0].replaceAll("-canary$", "");
        metricsCollector.record(
                "outbound",
                serviceName,
                destination.version(),
                result.statusCode(),
                result.durationMs(),
                result.retryCount()
        );

        HttpHeaders responseHeaders = result.headers() != null ? result.headers() : new HttpHeaders();

        return ResponseEntity
                .status(result.statusCode())
                .headers(responseHeaders)
                .body(result.body());
    }
}
