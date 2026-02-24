package com.mesh.controlplane.model;

public record ServiceDefinition(String id, String host, int port, String healthPath) {}
