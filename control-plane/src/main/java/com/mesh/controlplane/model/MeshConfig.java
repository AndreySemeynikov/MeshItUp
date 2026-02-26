package com.mesh.controlplane.model;

import java.util.List;

public record MeshConfig(
    int version,
    List<ServiceDefinition> services,
    List<RouteDefinition> routes,
    RetryPolicy retryPolicy) {}
