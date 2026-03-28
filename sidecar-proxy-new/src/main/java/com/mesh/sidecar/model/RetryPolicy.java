package com.mesh.sidecar.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Глобальная политика retry для всех маршрутов.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetryPolicy(
        int maxAttempts,                       // максимум попыток (включая первую)
        long delayMs,                          // пауза между попытками в мс
        List<Integer> retriableStatusCodes     // при каких кодах повторять (502, 503, 504)
) {
    /**
     * Дефолтная политика если control plane не прислал retryPolicy.
     */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, 500, List.of(502, 503, 504));
    }
}
