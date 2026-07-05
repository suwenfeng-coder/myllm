package com.example.myllm.support.chunking;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在分块器产出结果之上，统一补齐入库所需的文档级与位置元数据。
 */
public final class ChunkMetadataEnricher {

    public static final String KEY_SOURCE_FILE_NAME = "sourceFileName";
    public static final String KEY_CHAPTER_LOCATION = "chapterLocation";
    public static final String KEY_DOCUMENT_TYPE = "documentType";
    public static final String KEY_DOCUMENT_VERSION = "documentVersion";
    public static final String KEY_REQUESTED_PARSE_MODE = "requestedParseMode";
    public static final String KEY_APPLIED_PARSE_MODE = "appliedParseMode";
    public static final String KEY_PARSER_ENGINE = "parserEngine";
    public static final String KEY_PARSE_FALLBACK_REASON = "parseFallbackReason";
    private static final String UNKNOWN = "unknown";

    private ChunkMetadataEnricher() {
    }

    /**
     * @param chunk 分块器产出的分片
     * @param sourceFileName 原始文件名
     * @param documentType 文件扩展名或内容类型，如 docx、md
     * @param cleanerVersion 清洗规则版本
     * @param chunkStrategyVersion 分块策略版本
     * @param embeddingModelVersion 向量模型版本
     * @return 合并后的元数据，保证包含来源文件、位置、类型与版本
     */
    public static Map<String, Object> enrich(
            DocumentChunk chunk,
            String sourceFileName,
            String documentType,
            String cleanerVersion,
            String chunkStrategyVersion,
            String embeddingModelVersion) {
        return enrich(
                chunk,
                sourceFileName,
                documentType,
                cleanerVersion,
                chunkStrategyVersion,
                embeddingModelVersion,
                Map.of());
    }

    /**
     * 在基础分片元数据之上补充文档解析模式及实际引擎，便于检索结果追溯。
     */
    public static Map<String, Object> enrich(
            DocumentChunk chunk,
            String sourceFileName,
            String documentType,
            String cleanerVersion,
            String chunkStrategyVersion,
            String embeddingModelVersion,
            Map<String, Object> documentMetadata) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (chunk.metadata() != null && !chunk.metadata().isEmpty()) {
            merged.putAll(chunk.metadata());
        }
        merged.put(KEY_SOURCE_FILE_NAME, normalize(sourceFileName, UNKNOWN));
        merged.put(KEY_CHAPTER_LOCATION, resolveChapterLocation(chunk));
        merged.put(KEY_DOCUMENT_TYPE, normalize(documentType, UNKNOWN));
        merged.put(KEY_DOCUMENT_VERSION, composeDocumentVersion(
                cleanerVersion, chunkStrategyVersion, embeddingModelVersion));
        copyIfPresent(documentMetadata, merged, KEY_REQUESTED_PARSE_MODE, KEY_REQUESTED_PARSE_MODE);
        copyIfPresent(documentMetadata, merged, KEY_APPLIED_PARSE_MODE, KEY_APPLIED_PARSE_MODE);
        Object engine = firstPresent(documentMetadata, "actualParserEngine", "docForgeEngine", "parser");
        if (engine != null) {
            merged.put(KEY_PARSER_ENGINE, engine);
        }
        copyIfPresent(documentMetadata, merged, KEY_PARSE_FALLBACK_REASON, KEY_PARSE_FALLBACK_REASON);
        return Map.copyOf(merged);
    }

    private static void copyIfPresent(
            Map<String, Object> source,
            Map<String, Object> target,
            String sourceKey,
            String targetKey) {
        if (source == null) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value != null && !value.toString().isBlank()) {
            target.put(targetKey, value);
        }
    }

    private static Object firstPresent(Map<String, Object> source, String... keys) {
        if (source == null) {
            return null;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value;
            }
        }
        return null;
    }

    static String resolveChapterLocation(DocumentChunk chunk) {
        if (chunk.headingPath() != null && !chunk.headingPath().isBlank()) {
            return chunk.headingPath();
        }
        Integer start = chunk.startSourceIndex();
        Integer end = chunk.endSourceIndex();
        if (start != null && end != null) {
            if (start.equals(end)) {
                return "block:" + start;
            }
            return "block:" + start + "-" + end;
        }
        return "chunk:" + chunk.index();
    }

    private static String composeDocumentVersion(
            String cleanerVersion, String chunkStrategyVersion, String embeddingModelVersion) {
        return normalize(cleanerVersion, UNKNOWN)
                + "|"
                + normalize(chunkStrategyVersion, UNKNOWN)
                + "|"
                + normalize(embeddingModelVersion, UNKNOWN);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.strip();
    }
}
