package com.example.myllm.dto;

import java.util.List;

public record FileEmbeddingResponse(
        String fileId,
        String fileName,
        int chunkCount,
        int embeddingDimension,
        String cleanerVersion,
        int rawCharCount,
        int cleanedCharCount,
        int removedDuplicateBlocks,
        String requestedChunkStrategy,
        String appliedChunkStrategy,
        String message,
        String requestedParseMode,
        String parseMode,
        String parserEngine,
        Integer parsedPages,
        Integer doclingPages,
        Long parseDurationMs,
        List<String> attemptedParseModes,
        String parseFallbackReason,
        String originalStoragePath,
        String parsedStoragePath,
        String originalStorageFileName,
        String parsedStorageFileName,
        IngestPipelineTiming pipelineTiming) {
}
