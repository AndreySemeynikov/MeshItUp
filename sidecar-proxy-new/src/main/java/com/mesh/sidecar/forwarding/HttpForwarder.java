package com.mesh.sidecar.forwarding;

import com.mesh.sidecar.config.MeshProperties;
import com.mesh.sidecar.model.Destination;
import com.mesh.sidecar.model.ForwardResult;
import com.mesh.sidecar.model.RetryPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Модуль 3: HttpForwarder
 *
 * Пересылает HTTP-запрос к целевому destination.
 * Используется и для outbound (к другим сервисам), и для inbound (к localhost).
 * Реализует retry-логику согласно RetryPolicy.
 *
 * Важно: sidecar — это прокси. HTTP-ошибки (4xx/5xx) от бизнес-сервиса
 * не являются ошибками sidecar'а — это валидный ответ, который надо
 * прозрачно пропустить вызывающему. Поэтому RestClient настроен так,
 * чтобы НЕ бросать исключения на non-2xx статусах; статус и тело
 * извлекаются из ResponseEntity.
 */
@Component
public class HttpForwarder {

    private static final Logger log = LoggerFactory.getLogger(HttpForwarder.class);

    private static final List<String> SKIP_REQUEST_HEADERS = List.of(
            "host", "content-length"
    );

    private static final List<String> SKIP_RESPONSE_HEADERS = List.of(
            "transfer-encoding", "connection"
    );

    private final MeshProperties properties;
    private final RestClient restClient;

    public HttpForwarder(MeshProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;

        System.setProperty("http.keepAlive", "false");
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);

        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Пересылает запрос к выбранному destination с учётом retry политики.
     * Используется для outbound-запросов (через Router → к другим сервисам).
     */
    public ForwardResult forward(HttpServletRequest originalRequest,
                                 Destination destination,
                                 RetryPolicy retryPolicy) {

        String targetUrl = buildTargetUrl(originalRequest, destination);
        HttpMethod method = HttpMethod.valueOf(originalRequest.getMethod());
        HttpHeaders requestHeaders = buildRequestHeaders(originalRequest, destination);
        byte[] requestBody = readBody(originalRequest);

        return executeWithRetry(targetUrl, method, requestHeaders, requestBody,
                originalRequest, destination, retryPolicy);
    }

    /**
     * Пересылает входящий запрос к локальному бизнес-сервису (localhost:LOCAL_SERVICE_PORT).
     * Используется для inbound-запросов. Retry не применяется — сервис рядом в том же поде.
     */
    public ForwardResult forwardToLocalService(HttpServletRequest originalRequest) {
        String targetUrl = "http://localhost:" + properties.getLocalServicePort()
                + originalRequest.getRequestURI();
        String queryString = originalRequest.getQueryString();
        if (queryString != null && !queryString.isBlank()) {
            targetUrl += "?" + queryString;
        }

        HttpMethod method = HttpMethod.valueOf(originalRequest.getMethod());
        HttpHeaders requestHeaders = copyHeaders(originalRequest);
        byte[] requestBody = readBody(originalRequest);

        long startTime = System.nanoTime();

        try {
            ResponseEntity<byte[]> response = execute(method, targetUrl, requestHeaders, requestBody);

            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            HttpHeaders responseHeaders = filterResponseHeaders(response.getHeaders());

            return new ForwardResult(
                    response.getStatusCode().value(),
                    responseHeaders,
                    response.getBody(),
                    durationMs,
                    0,
                    "localhost",
                    "local"
            );
        } catch (ResourceAccessException e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.error("Inbound forward to localhost:{} failed: {}",
                    properties.getLocalServicePort(), e.getMessage());
            byte[] errorBody = "{\"error\":\"upstream connection error\"}".getBytes();
            return new ForwardResult(502, new HttpHeaders(), errorBody, durationMs,
                    0, "localhost", "local");
        }
    }

    // ---- private methods ----

    private ForwardResult executeWithRetry(String targetUrl, HttpMethod method,
                                           HttpHeaders requestHeaders, byte[] requestBody,
                                           HttpServletRequest originalRequest,
                                           Destination destination, RetryPolicy retryPolicy) {
        long startTime = System.nanoTime();
        int attempt = 0;
        ForwardResult lastResult = null;

        int maxAttempts = retryPolicy != null ? retryPolicy.maxAttempts() : 1;
        long delayMs = retryPolicy != null ? retryPolicy.delayMs() : 0;
        List<Integer> retriableCodes = retryPolicy != null ? retryPolicy.retriableStatusCodes() : List.of();

        while (attempt < maxAttempts) {
            attempt++;

            if (attempt > 1) {
                log.warn("Retry {}/{} for {} {} → {}, reason: {}",
                        attempt, maxAttempts, method, originalRequest.getRequestURI(),
                        destination.host(), lastResult != null ? lastResult.statusCode() : "connection error");
                if (delayMs > 0) {
                    sleep(delayMs);
                }
            }

            try {
                ResponseEntity<byte[]> response = execute(method, targetUrl, requestHeaders, requestBody);

                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                int statusCode = response.getStatusCode().value();
                HttpHeaders responseHeaders = filterResponseHeaders(response.getHeaders());

                lastResult = new ForwardResult(statusCode, responseHeaders, response.getBody(),
                        durationMs, attempt - 1, destination.host(), destination.version());

                if (retriableCodes.contains(statusCode) && attempt < maxAttempts) {
                    continue;
                }
                return lastResult;

            } catch (ResourceAccessException e) {
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                byte[] errorBody = "{\"error\":\"upstream connection error\"}".getBytes();
                lastResult = new ForwardResult(502, new HttpHeaders(), errorBody, durationMs,
                        attempt - 1, destination.host(), destination.version());

                if (attempt >= maxAttempts) {
                    log.error("All retries exhausted for {} {} → {}: {}",
                            method, originalRequest.getRequestURI(), destination.host(), e.getMessage());
                    return lastResult;
                }
            }
        }

        return lastResult != null ? lastResult
                : new ForwardResult(502, new HttpHeaders(), null, 0, attempt - 1,
                destination.host(), destination.version());
    }

    /**
     * Общий helper для HTTP-вызовов через RestClient.
     *
     * onStatus(any, no-op) отключает дефолтное поведение RestClient'а, при котором
     * статусы 4xx/5xx превращаются в RestClientResponseException. Для прокси такой
     * ответ — это не сбой, а нормальные данные от upstream'а, которые надо пропустить
     * дальше с тем же кодом и телом.
     *
     * Бросается только ResourceAccessException при сетевых проблемах (connection
     * refused, timeout и т.п.) — это реальный сбой инфраструктуры, его надо отличать
     * от HTTP-ошибки бизнес-сервиса.
     */
    private ResponseEntity<byte[]> execute(HttpMethod method, String url,
                                           HttpHeaders headers, byte[] body) {
        return restClient.method(method)
                .uri(url)
                .headers(h -> h.addAll(headers))
                .body(body)
                .retrieve()
                .onStatus(status -> true, (req, res) -> { /* не бросать на не-2xx */ })
                .toEntity(byte[].class);
    }

    private String buildTargetUrl(HttpServletRequest request, Destination destination) {
        StringBuilder url = new StringBuilder();
        url.append("http://")
                .append(destination.host())
                .append(":")
                .append(destination.port())
                .append(request.getRequestURI());

        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isBlank()) {
            url.append("?").append(queryString);
        }
        return url.toString();
    }

    private HttpHeaders buildRequestHeaders(HttpServletRequest request, Destination destination) {
        HttpHeaders headers = copyHeaders(request);

        if (!headers.containsKey("X-Request-Id")) {
            headers.set("X-Request-Id", UUID.randomUUID().toString());
        }

        headers.set("X-Mesh-Source", properties.getServiceId());
        headers.set("X-Mesh-Route-Version", destination.version());

        return headers;
    }

    /**
     * Копирует заголовки из оригинального запроса (без hop-by-hop).
     */
    private HttpHeaders copyHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Collections.list(request.getHeaderNames()).forEach(headerName -> {
            if (!SKIP_REQUEST_HEADERS.contains(headerName.toLowerCase())) {
                Collections.list(request.getHeaders(headerName))
                        .forEach(value -> headers.add(headerName, value));
            }
        });
        return headers;
    }

    private HttpHeaders filterResponseHeaders(HttpHeaders responseHeaders) {
        HttpHeaders filtered = new HttpHeaders();
        responseHeaders.forEach((name, values) -> {
            if (!SKIP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                filtered.addAll(name, values);
            }
        });
        return filtered;
    }

    private byte[] readBody(HttpServletRequest request) {
        try {
            return request.getInputStream().readAllBytes();
        } catch (IOException e) {
            log.warn("Failed to read request body: {}", e.getMessage());
            return new byte[0];
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}