package com.example.myllm.dto;

import java.util.Map;

public record VectorChunkResult(
        String fileId,
        String fileName,
        int chunkIndex,
        String chunkText,
        double similarity,
        String headingPath,
        int charCount,
        Integer tokenCount,
        String contentHash,
        Map<String, Object> metadata) {

    public VectorChunkResult(
            String fileId,
            String fileName,
            int chunkIndex,
            String chunkText,
            double similarity) {
        this(fileId, fileName, chunkIndex, chunkText, similarity, null, 0, null, null, Map.of());
    }

    public VectorChunkResult withSimilarity(double similarity) {
        return new VectorChunkResult(
                fileId,
                fileName,
                chunkIndex,
                chunkText,
                similarity,
                headingPath,
                charCount,
                tokenCount,
                contentHash,
                metadata);
    }
}
