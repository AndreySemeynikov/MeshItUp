package com.mesh.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mesh")
public record MeshProperties(String configPath) {}
