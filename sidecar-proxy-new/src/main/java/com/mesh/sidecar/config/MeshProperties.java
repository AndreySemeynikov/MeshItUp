package com.mesh.sidecar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "mesh")
public class MeshProperties {

    /**
     * Идентификатор сервиса рядом (например "api-gateway", "inventory-service").
     * Обязательный. Передаётся в control plane при запросе конфигурации.
     */
    private String serviceId;

    /**
     * Полный URL control plane.
     * Обязательный. Например: http://control-plane.mesh.svc.cluster.local:8080
     */
    private String controlPlaneUrl;

    /**
     * Порт на котором sidecar слушает исходящие HTTP-запросы от бизнес-сервиса (outbound).
     * Default: 15001
     */
    private int proxyPort = 15001;

    /**
     * Порт на котором sidecar слушает входящий трафик извне пода (inbound).
     * Kubernetes Service направляет трафик сюда вместо прямого порта бизнес-сервиса.
     * Default: 15006
     */
    private int inboundPort = 15006;

    /**
     * Порт бизнес-сервиса рядом с sidecar (localhost).
     * Inbound listener пересылает трафик сюда.
     * Default: 8080
     */
    private int localServicePort = 8080;

    /**
     * Порт для Prometheus endpoint.
     * Default: 15002
     */
    private int metricsPort = 15002;

    /**
     * Интервал в секундах между запросами конфигурации к control plane.
     * Default: 10
     */
    private int configRefreshInterval = 10;

    /**
     * Интервал в секундах между отправками агрегированных метрик в control plane.
     * Default: 30
     */
    private int metricsReportInterval = 30;

    // Getters and setters

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getControlPlaneUrl() {
        return controlPlaneUrl;
    }

    public void setControlPlaneUrl(String controlPlaneUrl) {
        this.controlPlaneUrl = controlPlaneUrl;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public int getInboundPort() {
        return inboundPort;
    }

    public void setInboundPort(int inboundPort) {
        this.inboundPort = inboundPort;
    }

    public int getLocalServicePort() {
        return localServicePort;
    }

    public void setLocalServicePort(int localServicePort) {
        this.localServicePort = localServicePort;
    }

    public int getMetricsPort() {
        return metricsPort;
    }

    public void setMetricsPort(int metricsPort) {
        this.metricsPort = metricsPort;
    }

    public int getConfigRefreshInterval() {
        return configRefreshInterval;
    }

    public void setConfigRefreshInterval(int configRefreshInterval) {
        this.configRefreshInterval = configRefreshInterval;
    }

    public int getMetricsReportInterval() {
        return metricsReportInterval;
    }

    public void setMetricsReportInterval(int metricsReportInterval) {
        this.metricsReportInterval = metricsReportInterval;
    }
}
