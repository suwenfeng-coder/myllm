package com.example.myllm.dto;

import java.time.LocalDateTime;

public record VectorFileSummary(
        String fileId,
        String fileName,
        String contentType,
        int chunkCount,
        LocalDateTime createdAt) {
}
