package com.mesh.sidecar.config;

import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /**
     * RestClient для обращения к control plane и к downstream сервисам.
     * Создаём один экземпляр builder — конкретные URL задаются при каждом вызове.
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Добавляет второй Tomcat Connector для inbound-трафика.
     *
     * Основной порт (server.port = 15001) обслуживает outbound-запросы от бизнес-сервиса.
     * Дополнительный порт (inbound-port = 15006) принимает входящий трафик извне пода.
     *
     * Оба порта обслуживаются одним и тем же DispatcherServlet → ProxyController.
     * Контроллер определяет режим (inbound/outbound) по request.getLocalPort().
     *
     * Это аналог того, как Envoy в Istio слушает на 15001 (outbound) и 15006 (inbound)
     * в одном процессе с разными listener'ами.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> inboundConnectorCustomizer(
            MeshProperties properties) {
        return factory -> {
            Connector inboundConnector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            inboundConnector.setPort(properties.getInboundPort());
            inboundConnector.setScheme("http");
            factory.addAdditionalTomcatConnectors(inboundConnector);
            log.info("Added inbound connector on port {}", properties.getInboundPort());
        };
    }
}
