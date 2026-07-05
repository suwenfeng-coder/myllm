package com.example.myllm.dto;

import java.util.List;

public record VectorSearchResponse(
        String query,
        int topK,
        List<VectorChunkResult> results) {
}
