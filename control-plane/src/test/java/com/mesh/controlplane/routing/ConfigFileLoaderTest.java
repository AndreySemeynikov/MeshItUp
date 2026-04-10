package com.mesh.controlplane.routing;

import com.mesh.controlplane.loader.ConfigFileLoader;
import com.mesh.controlplane.loader.ConfigFileLoader.ParsedConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFileLoaderTest {

    private final ConfigFileLoader loader = new ConfigFileLoader();

    @Test
    void shouldParseValidConfigFile() {
        ParsedConfig config = loader.load("src/test/resources/mesh-config.yaml");

        assertNotNull(config);
        assertEquals(2, config.services().size());
        assertEquals(1, config.routes().size());
        assertNotNull(config.retryPolicy());

        // Verify services
        assertEquals("api-gateway", config.services().get(0).id());
        assertEquals("inventory-service", config.services().get(1).id());
        assertEquals(8080, config.services().get(0).port());
        assertEquals("/health", config.services().get(0).healthPath());

        // Verify routes
        assertEquals("api-gateway", config.routes().get(0).source());
        assertEquals("/api/inventory/**", config.routes().get(0).pathPattern());
        assertEquals(1, config.routes().get(0).destinations().size());
        assertEquals("inventory-service", config.routes().get(0).destinations().get(0).serviceId());
        assertEquals("stable", config.routes().get(0).destinations().get(0).version());
        assertEquals(100, config.routes().get(0).destinations().get(0).weight());

        // Verify retry policy
        assertEquals(3, config.retryPolicy().maxAttempts());
        assertEquals(500, config.retryPolicy().delayMs());
        assertEquals(3, config.retryPolicy().retriableStatusCodes().size());
    }

    @Test
    void shouldThrowOnMissingFile() {
        assertThrows(IllegalStateException.class, () ->
                loader.load("nonexistent.yaml"));
    }
}
