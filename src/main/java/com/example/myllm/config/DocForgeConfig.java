package com.example.myllm.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(DocForgeProperties.class)
public class DocForgeConfig {

    private static final Logger log = LoggerFactory.getLogger(DocForgeConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "docforge", name = "enabled", havingValue = "true", matchIfMissing = true)
    RestClient docforgeRestClient(DocForgeProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory);

        if (!properties.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }

        RestClient client = builder.build();
        if (properties.healthCheckOnStartup()) {
            probeStartupHealth(client);
        }
        return client;
    }

    private static void probeStartupHealth(RestClient client) {
        try {
            var ready = client.get().uri("/ready").retrieve().body(DocForgeReadyResponse.class);
            if (ready != null && ready.engineInitialized()) {
                log.info("DocForge 服务已就绪: engines={}", ready.engines());
            } else {
                log.warn("DocForge 服务 /ready 响应异常，远程解析可能不可用");
            }
        } catch (Exception e) {
            log.warn("DocForge 服务启动探测失败，远程解析可能不可用: {}", e.getMessage());
        }
    }

    private record DocForgeReadyResponse(
            String status,
            @JsonProperty("engine_initialized") boolean engineInitialized,
            java.util.List<String> engines) {
    }
}
