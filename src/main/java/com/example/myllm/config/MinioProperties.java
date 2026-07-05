package com.example.myllm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        boolean enabled,
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String uploadsPrefix,
        String parsedPrefix,
        boolean ensureBucketOnStartup) {

    public MinioProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://127.0.0.1:9000";
        }
        if (accessKey == null || accessKey.isBlank()) {
            accessKey = "minioadmin";
        }
        if (secretKey == null || secretKey.isBlank()) {
            secretKey = "minioadmin";
        }
        if (bucket == null || bucket.isBlank()) {
            bucket = "myllm";
        }
        if (uploadsPrefix == null || uploadsPrefix.isBlank()) {
            uploadsPrefix = "uploads";
        }
        if (parsedPrefix == null || parsedPrefix.isBlank()) {
            parsedPrefix = "parsed";
        }
    }
}
