package com.example.myllm.service;

import com.example.myllm.dto.VectorChunkResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 ParadeDB/pg_search 的 BM25 稀疏召回服务。
 *
 * <p>服务不会在在线请求中安装扩展或创建索引。每次查询前先检查 {@code pg_search}
 * 和 BM25 索引；能力缺失或查询失败时返回可观测的降级原因，由检索编排层回退到 Dense。</p>
 */
@Service
public class Bm25RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(Bm25RetrievalService.class);
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};
    private static final Pattern SQL_IDENTIFIER =
            Pattern.compile("[\\w&&[^\\d]]\\w*(?:\\.[\\w&&[^\\d]]\\w*)?");

    private final JdbcTemplate vectorJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String tableName;
    private final String indexName;

    public Bm25RetrievalService(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${vector.table-name:file_embeddings}") String tableName,
            @Value("${rag.retrieval.bm25-index-name:file_embeddings_bm25_idx}") String indexName) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.objectMapper = objectMapper;
        this.tableName = requireIdentifier(tableName, "vector.table-name");
        this.indexName = requireIdentifier(indexName, "rag.retrieval.bm25-index-name");
    }

    /**
     * 执行 BM25 TopK 召回。
     *
     * @param query 改写后的检索问题
     * @param topK BM25 候选数量
     * @param fileIds 可选的文件范围
     * @return 命中、耗时以及是否具备 BM25 能力
     */
    public Bm25SearchResult search(String query, int topK, List<String> fileIds) {
        long startNanos = System.nanoTime();
        if (query == null || query.isBlank()) {
            return unavailable(startNanos, "BM25_QUERY_EMPTY");
        }

        try {
            String capabilityError = capabilityError();
            if (capabilityError != null) {
                return unavailable(startNanos, capabilityError);
            }

            int limit = Math.max(1, topK);
            List<String> normalizedFileIds = normalizeFileIds(fileIds);
            StringBuilder sql = new StringBuilder("SELECT file_id, file_name, chunk_index, chunk_text,")
                    .append(" heading_path, COALESCE(char_count, 0) AS char_count, token_count,")
                    .append(" content_hash, metadata, pdb.score(id) AS bm25_score")
                    .append(" FROM ").append(tableName)
                    .append(" WHERE (chunk_text ||| ? OR file_name ||| ?)");
            List<Object> params = new ArrayList<>();
            params.add(query.trim());
            params.add(query.trim());

            appendFileFilter(sql, params, normalizedFileIds);
            sql.append(" ORDER BY pdb.score(id) DESC LIMIT ?");
            params.add(limit);

            List<VectorChunkResult> hits = vectorJdbcTemplate.query(
                    sql.toString(),
                    (rs, rowNum) -> new VectorChunkResult(
                            rs.getString("file_id"),
                            rs.getString("file_name"),
                            rs.getInt("chunk_index"),
                            rs.getString("chunk_text"),
                            rs.getDouble("bm25_score"),
                            rs.getString("heading_path"),
                            rs.getInt("char_count"),
                            (Integer) rs.getObject("token_count"),
                            rs.getString("content_hash"),
                            parseMetadata(rs.getString("metadata"))),
                    params.toArray());
            long durationMs = elapsedMillis(startNanos);
            log.info("BM25 检索完成 queryLen={} topK={} fileFilterCount={} hits={} durationMs={}",
                    query.length(), limit, normalizedFileIds.size(), hits.size(), durationMs);
            return new Bm25SearchResult(hits, true, durationMs, null);
        } catch (Exception e) {
            String reason = "BM25_QUERY_FAILED:" + e.getClass().getSimpleName() + ":" + safeMessage(e);
            log.warn("BM25 检索不可用，将回退 Dense reason={}", reason);
            return unavailable(startNanos, reason);
        }
    }

    private String capabilityError() {
        Boolean extensionInstalled = vectorJdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_search')",
                Boolean.class);
        if (!Boolean.TRUE.equals(extensionInstalled)) {
            return "PG_SEARCH_EXTENSION_MISSING";
        }
        Boolean indexExists = vectorJdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL",
                Boolean.class,
                indexName);
        if (!Boolean.TRUE.equals(indexExists)) {
            return "BM25_INDEX_MISSING:" + indexName;
        }
        return null;
    }

    private static void appendFileFilter(
            StringBuilder sql,
            List<Object> params,
            List<String> normalizedFileIds) {
        if (normalizedFileIds.isEmpty()) {
            return;
        }
        sql.append(" AND file_id IN (");
        for (int i = 0; i < normalizedFileIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
            params.add(normalizedFileIds.get(i));
        }
        sql.append(')');
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (JsonProcessingException e) {
            log.debug("忽略无法解析的 BM25 分片 metadata", e);
            return Map.of();
        }
    }

    private static List<String> normalizeFileIds(List<String> fileIds) {
        if (fileIds == null) {
            return List.of();
        }
        return fileIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String requireIdentifier(String value, String propertyName) {
        if (value == null || !SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(propertyName + " 不是合法 SQL 标识符");
        }
        return value;
    }

    private static Bm25SearchResult unavailable(long startNanos, String reason) {
        return new Bm25SearchResult(List.of(), false, elapsedMillis(startNanos), reason);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "no-message";
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    /** BM25 查询结果；{@code available=false} 时编排层应回退到 Dense。 */
    public record Bm25SearchResult(
            List<VectorChunkResult> hits,
            boolean available,
            long durationMs,
            String fallbackReason) {

        public Bm25SearchResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }
}
