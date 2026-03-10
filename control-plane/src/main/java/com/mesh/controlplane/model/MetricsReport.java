package com.mesh.controlplane.model;

import java.time.Instant;
import java.util.List;

public record MetricsReport(
    String proxyId,
    String serviceId,
    Instant timestamp,
    int windowSeconds,
    List<MetricsEntry> entries) {}
