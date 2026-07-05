package com.example.myllm.support.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * 对任意分块策略的输出做硬上限二次切分，避免 merge 后产生超大 chunk。
 */
public final class ChunkSizeLimiter {

    private ChunkSizeLimiter() {
    }

    public static List<DocumentChunk> enforceMaxSize(
            List<DocumentChunk> chunks,
            int maxSize,
            RecursiveDocumentChunker recursiveChunker) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        int safeMax = Math.max(200, maxSize);
        List<DocumentChunk> result = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            String content = chunk.embeddingContent();
            if (content == null || content.length() <= safeMax) {
                result.add(reindex(result.size(), chunk));
                continue;
            }
            for (String piece : recursiveChunker.split(content)) {
                result.add(DocumentChunkFactory.of(
                        result.size(),
                        piece,
                        chunk.headingPath(),
                        chunk.startSourceIndex(),
                        chunk.endSourceIndex(),
                        chunk.metadata()));
            }
        }
        return result;
    }

    private static DocumentChunk reindex(int index, DocumentChunk chunk) {
        if (chunk.index() == index) {
            return chunk;
        }
        return DocumentChunkFactory.of(
                index,
                chunk.embeddingContent(),
                chunk.headingPath(),
                chunk.startSourceIndex(),
                chunk.endSourceIndex(),
                chunk.metadata());
    }
}
