package com.example.myllm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docforge")
public record DocForgeProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String outputFormat,
        int syncMaxSizeMb,
        int asyncMaxSizeMb,
        int connectTimeoutMs,
        int readTimeoutMs,
        int asyncPollIntervalMs,
        long asyncPollMaxWaitMs,
        boolean healthCheckOnStartup) {

    public DocForgeProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8000";
        }
        if (outputFormat == null || outputFormat.isBlank()) {
            outputFormat = "markdown";
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }
}
