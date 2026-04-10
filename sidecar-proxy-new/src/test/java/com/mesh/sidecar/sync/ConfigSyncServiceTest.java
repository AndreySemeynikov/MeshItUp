/*
package com.mesh.sidecar.sync;

import com.mesh.sidecar.config.MeshProperties;
import com.mesh.sidecar.model.MeshConfig;
import com.mesh.sidecar.model.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigSyncServiceTest {

    @Mock
    private MeshProperties properties;

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.build()).thenReturn(restClient);
        when(properties.getServiceId()).thenReturn("test-service");
        when(properties.getControlPlaneUrl()).thenReturn("http://control-plane:8080");
        when(properties.getProxyPort()).thenReturn(15001);
    }

    @Test
    @DisplayName("isConfigured() возвращает false до первого sync")
    void shouldNotBeConfiguredInitially() {
        // Сначала мокируем sync чтобы он не выполнялся в PostConstruct
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersUriSpec);
        when(responseSpec.body(MeshConfig.class)).thenReturn(null);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);

        ConfigSyncService service = new ConfigSyncService(properties, restClientBuilder);

        // После init() который возвращает null — конфиг не установлен
        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("После успешного sync getConfig() возвращает конфигурацию")
    void shouldReturnConfigAfterSuccessfulSync() {
        MeshConfig expectedConfig = new MeshConfig(1, List.of(), RetryPolicy.defaultPolicy());

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MeshConfig.class)).thenReturn(expectedConfig);

        ConfigSyncService service = new ConfigSyncService(properties, restClientBuilder);

        assertThat(service.isConfigured()).isTrue();
        assertThat(service.getConfig()).isEqualTo(expectedConfig);
    }

    @Test
    @DisplayName("При ошибке control plane — кэшированная конфигурация сохраняется")
    void shouldKeepCachedConfigOnSyncFailure() {
        MeshConfig cachedConfig = new MeshConfig(1, List.of(), RetryPolicy.defaultPolicy());

        // Первый вызов — успешный
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MeshConfig.class))
                .thenReturn(cachedConfig)  // первый вызов — успех
                .thenThrow(new ResourceAccessException("Connection refused")); // второй — ошибка

        ConfigSyncService service = new ConfigSyncService(properties, restClientBuilder);

        assertThat(service.isConfigured()).isTrue();
        assertThat(service.getConfig()).isEqualTo(cachedConfig);

        // Второй sync — ошибка
        service.sync();

        // Конфиг должен остаться прежним
        assertThat(service.isConfigured()).isTrue();
        assertThat(service.getConfig()).isEqualTo(cachedConfig);
    }

    @Test
    @DisplayName("Конфиг обновляется только при изменении version")
    void shouldUpdateConfigOnlyWhenVersionChanges() {
        MeshConfig v1 = new MeshConfig(1, List.of(), RetryPolicy.defaultPolicy());
        MeshConfig v2 = new MeshConfig(2, List.of(), RetryPolicy.defaultPolicy());

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MeshConfig.class))
                .thenReturn(v1)
                .thenReturn(v1)  // та же версия — не обновляем
                .thenReturn(v2); // новая версия — обновляем

        ConfigSyncService service = new ConfigSyncService(properties, restClientBuilder);
        assertThat(service.getConfig().version()).isEqualTo(1);

        service.sync(); // v1 снова — без изменений
        assertThat(service.getConfig().version()).isEqualTo(1);

        service.sync(); // v2 — обновляем
        assertThat(service.getConfig().version()).isEqualTo(2);
    }
}
*/
