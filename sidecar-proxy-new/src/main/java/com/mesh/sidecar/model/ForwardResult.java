package com.mesh.sidecar.model;

import org.springframework.http.HttpHeaders;

/**
 * Результат пересылки HTTP-запроса к downstream сервису.
 */
public record ForwardResult(
        int statusCode,       // HTTP status code от downstream
        HttpHeaders headers,  // заголовки ответа от downstream
        byte[] body,          // тело ответа (может быть null или пустым)
        long durationMs,      // время обработки в миллисекундах
        int retryCount,       // сколько retry было выполнено (0 если с первого раза)
        String destination,   // имя destination (host) для метрик
        String version        // версия destination ("stable" или "canary")
) {}
