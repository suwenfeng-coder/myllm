package com.example.myllm.support.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从纯文本或结构化字段构建 {@link DocumentChunk}。
 */
public final class DocumentChunkFactory {

    private DocumentChunkFactory() {
    }

    public static List<DocumentChunk> fromPlainTexts(List<String> texts) {
        return fromPlainTexts(texts, Map.of());
    }

    public static List<DocumentChunk> fromPlainTexts(List<String> texts, Map<String, Object> sharedMetadata) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (texts == null) {
            return List.of();
        }
        for (int i = 0; i < texts.size(); i++) {
            chunks.add(of(i, texts.get(i), null, null, null, sharedMetadata));
        }
        return chunks;
    }

    public static DocumentChunk of(
            int index,
            String embeddingContent,
            String headingPath,
            Integer startSourceIndex,
            Integer endSourceIndex,
            Map<String, Object> metadata) {
        String normalized = embeddingContent == null ? "" : embeddingContent.strip();
        String path = headingPath == null || headingPath.isBlank() ? null : headingPath.strip();
        String display = ChunkTextComposer.displayText(path, normalized);
        return new DocumentChunk(
                index,
                normalized,
                display,
                path,
                ChunkMetadataBuilder.charCount(normalized),
                ChunkMetadataBuilder.estimateTokenCount(normalized),
                ChunkMetadataBuilder.contentHash(normalized),
                startSourceIndex,
                endSourceIndex,
                metadata == null ? Map.of() : metadata);
    }

    public static List<DocumentChunk> reindex(List<DocumentChunk> chunks) {
        List<DocumentChunk> reindexed = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            reindexed.add(of(
                    i,
                    chunk.embeddingContent(),
                    chunk.headingPath(),
                    chunk.startSourceIndex(),
                    chunk.endSourceIndex(),
                    chunk.metadata()));
        }
        return reindexed;
    }
}
