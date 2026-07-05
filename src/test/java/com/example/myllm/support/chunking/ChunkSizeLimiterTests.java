package com.example.myllm.support.chunking;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkSizeLimiterTests {

    private final RecursiveDocumentChunker recursive =
            new RecursiveDocumentChunker(300, 80, 20, false, 600, 80, 75);

    @Test
    void splitsOversizedMergedChunks() {
        String oversized = "超长段落。".repeat(120);
        DocumentChunk chunk = DocumentChunkFactory.of(0, oversized, "章节", 0, 0, null);

        List<DocumentChunk> limited = ChunkSizeLimiter.enforceMaxSize(List.of(chunk), 300, recursive);

        assertTrue(limited.size() > 1);
        assertTrue(limited.stream().allMatch(item -> item.embeddingContent().length() <= 300));
    }
}
