package com.mesh.controlplane.model;

public record Destination(String serviceId, String host, int port, String version, int weight) {}
