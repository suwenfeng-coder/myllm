package com.example.myllm.dto;

import java.time.LocalDateTime;

public record RequestLogItemResponse(
        TransactionInfo transaction,
        RagInfo rag) {

    public record TransactionInfo(
            Long id,
            LocalDateTime createdAt,
            String provider,
            String model,
            String status,
            String requestType,
            Long durationMs,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            String errorMessage) {
    }

    public record RagInfo(
            Long id,
            boolean ragEnabled,
            boolean ragAttempted,
            boolean ragApplied,
            int retrievedChunks,
            String selectedFileIds,
            String ragStatus,
            String ragError,
            String normalizedQuery,
            String rewrittenQuery,
            String rewriteStrategy,
            String queryIntent,
            String retrievalMode,
            int denseHitCount,
            int bm25HitCount,
            int fusedHitCount,
            long bm25DurationMs,
            long rrfDurationMs,
            String bm25FallbackReason,
            boolean graphAttempted,
            boolean graphAvailable,
            int graphSeedCount,
            int graphHitCount,
            int graphShadowOverlapCount,
            long graphDurationMs,
            String graphFallbackReason,
            LocalDateTime createdAt) {
    }
}
