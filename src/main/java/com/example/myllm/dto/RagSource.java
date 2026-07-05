package com.example.myllm.dto;

public record RagSource(
        String fileName,
        int chunkIndex,
        double similarity,
        String snippet,
        String headingPath,
        String contentHash) {

    public RagSource(String fileName, int chunkIndex, double similarity, String snippet) {
        this(fileName, chunkIndex, similarity, snippet, null, null);
    }
}
