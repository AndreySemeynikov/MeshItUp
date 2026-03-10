package com.mesh.controlplane.model;

public record MetricsEntry(
    String destination, String version, int requestCount, int errorCount, long avgLatencyMs) {}
