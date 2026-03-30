package com.mesh.sidecar.sync;

import com.mesh.sidecar.config.MeshProperties;
import com.mesh.sidecar.model.MeshConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Модуль 1: ConfigSyncService
 *
 * Периодически запрашивает конфигурацию у control plane и обновляет локальное хранилище.
 * При недоступности control plane работает с кэшированной конфигурацией.
 */
@Service
public class ConfigSyncService {

    private static final Logger log = LoggerFactory.getLogger(ConfigSyncService.class);

    private final MeshProperties properties;
    private final RestClient restClient;

    /**
     * Потокобезопасное хранение текущей конфигурации.
     * null означает что конфигурация ещё не была получена.
     */
    private final AtomicReference<MeshConfig> currentConfig = new AtomicReference<>(null);

    public ConfigSyncService(MeshProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    /**
     * Eager init — выполняем первый запрос сразу при старте приложения.
     * Это важно: sidecar должен получить конфигурацию до того как начнёт принимать трафик.
     */
    @PostConstruct
    public void init() {
        log.info("Sidecar started for service={}, proxy port={}",
                properties.getServiceId(), properties.getProxyPort());
        sync();
    }

    /**
     * Scheduled sync — периодически обновляет конфигурацию.
     * fixedDelay означает что интервал считается ПОСЛЕ окончания предыдущего вызова.
     */
    @Scheduled(fixedDelayString = "#{meshProperties.configRefreshInterval * 1000}")
    public void sync() {
        String url = properties.getControlPlaneUrl()
                + "/api/v1/config?serviceId=" + properties.getServiceId();
        try {
            MeshConfig freshConfig = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(MeshConfig.class);

            if (freshConfig == null) {
                log.warn("Config sync returned null response from {}", url);
                return;
            }

            MeshConfig existing = currentConfig.get();
            if (existing == null || existing.version() != freshConfig.version()) {
                currentConfig.set(freshConfig);
                log.debug("Config updated to version={}, routes={}",
                        freshConfig.version(),
                        freshConfig.routes() != null ? freshConfig.routes().size() : 0);
            }

        } catch (RestClientException e) {
            MeshConfig cached = currentConfig.get();
            if (cached != null) {
                log.warn("Config sync failed: {}. Using cached config v{}", e.getMessage(), cached.version());
            } else {
                log.warn("Config sync failed: {}. No cached config available — sidecar will return 503", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Config sync failed with unexpected error: {}", e.getMessage());
        }
    }

    /**
     * Возвращает текущую конфигурацию.
     * Возвращает null если конфигурация ещё не была получена ни разу.
     */
    public MeshConfig getConfig() {
        return currentConfig.get();
    }

    /**
     * Проверяет, готов ли sidecar к обработке запросов.
     */
    public boolean isConfigured() {
        return currentConfig.get() != null;
    }
}
