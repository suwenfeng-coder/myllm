package com.example.myllm.dto;

/** 解析耗时预估响应。 */
public record ParseEstimateResponse(
        String parseMode,
        long fileSizeBytes,
        long etaSeconds,
        String source,
        String message) {
}
