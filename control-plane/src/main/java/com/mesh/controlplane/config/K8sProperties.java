package com.mesh.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "k8s")
public record K8sProperties(String namespace) {}
