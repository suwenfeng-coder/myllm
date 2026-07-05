package com.example.myllm.support.retrieval;

import java.util.List;

public record QueryRewriteResult(
        String originalQuery,
        String normalizedQuery,
        String retrievalQuery,
        QueryIntent intent,
        List<String> filenameHints,
        boolean skipRag,
        QueryRewriteStrategy strategy) {
}
