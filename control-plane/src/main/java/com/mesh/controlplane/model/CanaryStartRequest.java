package com.mesh.controlplane.model;

import java.util.Map;

public record CanaryStartRequest(
    String serviceId,
    String canaryImage,
    Map<String, String> canaryEnv,
    Integer initialWeight,
    Integer weightStep,
    Double errorThreshold) {}
