package com.example.myllm.support.minio;

public record MinioStoredObject(
        String bucket,
        String objectPath,
        String fileName,
        String contentType,
        long sizeBytes) {
}
