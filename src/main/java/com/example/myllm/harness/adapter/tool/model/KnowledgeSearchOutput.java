package com.example.myllm.harness.adapter.tool.model;

import java.util.List;

public record KnowledgeSearchOutput(
        String originalQuery,
        String retrievalQuery,
        String queryIntent,
        String retrievalMode,
        int rawHitCount,
        int acceptedHitCount,
        List<String> effectiveFileIds,
        String bm25FallbackReason,
        String graphFallbackReason,
        List<KnowledgeSearchCitation> citations) {
}
