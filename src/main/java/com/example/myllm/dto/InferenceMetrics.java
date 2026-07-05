package com.example.myllm.dto;

public record InferenceMetrics(
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        Double inferenceSpeedTps,
        Long promptEvalDurationMs,
        Long evalDurationMs,
        Long loadDurationMs,
        Long modelTotalDurationMs,
        Long wallClockDurationMs
) {
}
