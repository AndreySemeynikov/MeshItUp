package com.mesh.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "canary")
public record CanaryProperties(
    int evaluationInterval,
    int defaultInitialWeight,
    int defaultWeightStep,
    double defaultErrorThreshold,
    int successCountToPromote,
    int readyTimeoutSeconds) {}
