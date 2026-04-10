package com.mesh.sidecar.proxy;

import com.mesh.sidecar.model.MeshConfig;
import com.mesh.sidecar.model.RetryPolicy;
import com.mesh.sidecar.sync.ConfigSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "MESH_SERVICE_ID=test-service",
        "MESH_CONTROL_PLANE_URL=http://localhost:9999",
        "INBOUND_PORT=15006",
        "LOCAL_SERVICE_PORT=8080"
})
class ProxyControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConfigSyncService configSyncService;

    @Test
    @DisplayName("Возвращает 503 если sidecar ещё не сконфигурирован (outbound)")
    void shouldReturn503WhenNotConfigured() throws Exception {
        when(configSyncService.isConfigured()).thenReturn(false);

        mockMvc.perform(get("/api/inventory/items"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("sidecar not configured yet"));
    }

    @Test
    @DisplayName("Возвращает 503 если маршрут не найден (outbound)")
    void shouldReturn503WhenNoRouteFound() throws Exception {
        MeshConfig config = new MeshConfig(
                1,
                List.of(),
                RetryPolicy.defaultPolicy()
        );

        when(configSyncService.isConfigured()).thenReturn(true);
        when(configSyncService.getConfig()).thenReturn(config);

        mockMvc.perform(get("/api/unknown/path"))
                .andExpect(status().isServiceUnavailable());
    }
}
