package com.example.myllm.support.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkMetadataEnricherTests {

    @Test
    void enrichAddsRequiredFieldsForPlainChunk() {
        DocumentChunk chunk = DocumentChunkFactory.of(2, "正文内容", null, null, null, Map.of());

        Map<String, Object> metadata = ChunkMetadataEnricher.enrich(
                chunk, "guide.txt", "txt", "conservative-v1", "structural-char-v1", "bge-m3-v1");

        assertEquals("guide.txt", metadata.get(ChunkMetadataEnricher.KEY_SOURCE_FILE_NAME));
        assertEquals("chunk:2", metadata.get(ChunkMetadataEnricher.KEY_CHAPTER_LOCATION));
        assertEquals("txt", metadata.get(ChunkMetadataEnricher.KEY_DOCUMENT_TYPE));
        assertEquals(
                "conservative-v1|structural-char-v1|bge-m3-v1",
                metadata.get(ChunkMetadataEnricher.KEY_DOCUMENT_VERSION));
    }

    @Test
    void enrichUsesHeadingPathAsChapterLocation() {
        DocumentChunk chunk = DocumentChunkFactory.of(
                0, "数据库配置说明", "安装指南 > 数据库", 2, 4, Map.of("blockTypes", java.util.List.of("HEADING")));

        Map<String, Object> metadata = ChunkMetadataEnricher.enrich(
                chunk, "manual.docx", "docx", "conservative-v1", "structural-char-v1", "bge-m3-v1");

        assertEquals("安装指南 > 数据库", metadata.get(ChunkMetadataEnricher.KEY_CHAPTER_LOCATION));
        assertTrue(metadata.containsKey("blockTypes"));
    }

    @Test
    void enrichUsesBlockRangeWhenHeadingMissing() {
        DocumentChunk chunk = DocumentChunkFactory.of(1, "段落文本", null, 5, 7, Map.of());

        Map<String, Object> metadata = ChunkMetadataEnricher.enrich(
                chunk, "notes.md", "md", "conservative-v1", "structural-char-v1", "bge-m3-v1");

        assertEquals("block:5-7", metadata.get(ChunkMetadataEnricher.KEY_CHAPTER_LOCATION));
    }

    @Test
    void enrichCarriesParserAuditMetadata() {
        DocumentChunk chunk = DocumentChunkFactory.of(0, "PDF正文", null, 0, 0, Map.of());

        Map<String, Object> metadata = ChunkMetadataEnricher.enrich(
                chunk,
                "policy.pdf",
                "pdf",
                "conservative-v1",
                "structural-char-v1",
                "bge-m3-v1",
                Map.of(
                        "requestedParseMode", "auto",
                        "appliedParseMode", "docling",
                        "actualParserEngine", "docling",
                        "parseFallbackReason", "maker unavailable"));

        assertEquals("auto", metadata.get(ChunkMetadataEnricher.KEY_REQUESTED_PARSE_MODE));
        assertEquals("docling", metadata.get(ChunkMetadataEnricher.KEY_APPLIED_PARSE_MODE));
        assertEquals("docling", metadata.get(ChunkMetadataEnricher.KEY_PARSER_ENGINE));
        assertEquals("maker unavailable", metadata.get(ChunkMetadataEnricher.KEY_PARSE_FALLBACK_REASON));
    }
}
