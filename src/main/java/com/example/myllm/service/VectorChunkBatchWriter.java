package com.example.myllm.service;

import com.example.myllm.support.chunking.ChunkMetadataEnricher;
import com.example.myllm.support.chunking.ChunkingResult;
import com.example.myllm.support.chunking.DocumentChunk;
import com.example.myllm.support.document.CleaningReport;
import com.example.myllm.support.retrieval.EmbeddingInputComposer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 批量 embedding 与 JDBC batch 写入 pgvector。
 *
 * <p>本类是入库性能优化的核心：先按配置批量调用 embedding 模型，再使用 JDBC batch 一次写入同批分片。
 * 它同时负责把清洗版本、分块策略、解析元数据、内容哈希和模型版本写入 metadata，方便后续检索诊断和
 * 模型/分块策略迁移。</p>
 */
@Component
public class VectorChunkBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(VectorChunkBatchWriter.class);
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[\\w&&[^\\d]]\\w*");

    private final JdbcTemplate vectorJdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final String tableName;
    private final int batchSize;
    private final int expectedDimension;
    private final String chunkStrategyVersion;
    private final String embeddingModelName;
    private final String embeddingModelVersion;

    public VectorChunkBatchWriter(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            EmbeddingModel embeddingModel,
            ObjectMapper objectMapper,
            @Value("${vector.table-name:file_embeddings}") String tableName,
            @Value("${rag.embedding.batch-size:32}") int batchSize,
            @Value("${rag.embedding.expected-dimension:1024}") int expectedDimension,
            @Value("${rag.chunking.strategy-version:structural-token-v1}") String chunkStrategyVersion,
            @Value("${spring.ai.ollama.embedding.options.model:bge-m3}") String ollamaEmbeddingModel,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String openAiEmbeddingModel,
            @Value("${llm.provider:ollama}") String llmProvider,
            @Value("${rag.embedding.model-version:bge-m3-v1}") String embeddingModelVersion) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        if (tableName == null || !SQL_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("vector.table-name 不是合法 SQL 标识符");
        }
        this.tableName = tableName;
        this.batchSize = Math.max(1, Math.min(128, batchSize));
        this.expectedDimension = expectedDimension;
        this.chunkStrategyVersion = chunkStrategyVersion;
        this.embeddingModelName = "openai-compatible".equals(llmProvider) ? openAiEmbeddingModel : ollamaEmbeddingModel;
        this.embeddingModelVersion = embeddingModelVersion;
    }

    public record WriteRequest(
            String fileId,
            String fileName,
            String contentType,
            long fileSizeBytes,
            CleaningReport cleaningReport,
            ChunkingResult chunkingResult,
            Map<String, Object> parseMetadata) {}

    public record WriteProgress(int embedded, int total) {}

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(WriteProgress progress);
    }

    /**
     * 批量生成并写入所有分片向量。
     *
     * <p>写入前会校验每个向量的维度和有限数值，防止模型切换或异常输出污染同一向量表。当前物理列仍是
     * pgvector 的通用 {@code vector}，因此这里的应用层校验是保护旧表的最后一道门；生产化后应配合
     * {@code vector(1024)} 或模型版本表。</p>
     *
     * @return 实际 embedding 维度
     */
    @SuppressWarnings({"java:S3776", "java:S6909"})
    public int writeAll(WriteRequest request, List<DocumentChunk> chunks, ProgressCallback progressCallback) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("分块列表不能为空");
        }
        String insertSql = "INSERT INTO " + tableName
                + " (file_id, file_name, content_type, file_size_bytes, chunk_index,"
                + " chunk_text, embedding_content, heading_path, char_count, token_count,"
                + " content_hash, start_source_index, end_source_index,"
                + " cleaner_version, chunk_strategy_version, embedding_model, embedding_model_version,"
                + " requested_chunk_strategy, applied_chunk_strategy, metadata, embedding)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)";

        int dimension = 0;
        int total = chunks.size();
        ProgressCallback callback = progressCallback == null ? ignored -> {} : progressCallback;
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(chunks.size(), start + batchSize);
            List<DocumentChunk> batchChunks = chunks.subList(start, end);
            List<String> inputs = new ArrayList<>(batchChunks.size());
            for (DocumentChunk chunk : batchChunks) {
                inputs.add(EmbeddingInputComposer.compose(request.fileName(), chunk.embeddingContent()));
            }
            List<float[]> embeddings = embeddingModel.embed(inputs);
            if (embeddings.size() != batchChunks.size()) {
                throw new IllegalStateException("向量模型返回数量与分块数量不一致");
            }
            for (float[] embedding : embeddings) {
                validateEmbedding(embedding);
                dimension = embedding.length;
            }
            vectorJdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() { // NOSONAR
                @Override
                public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                    DocumentChunk chunk = batchChunks.get(i);
                    String embeddingInput = inputs.get(i);
                    String metadataJson = toMetadataJson(ChunkMetadataEnricher.enrich(
                            chunk,
                            request.fileName(),
                            request.contentType(),
                            request.cleaningReport().cleanerVersion(),
                            chunkStrategyVersion,
                            embeddingModelVersion,
                            request.parseMetadata()));
                    ps.setString(1, request.fileId());
                    ps.setString(2, request.fileName());
                    ps.setString(3, request.contentType());
                    ps.setLong(4, request.fileSizeBytes());
                    ps.setInt(5, chunk.index());
                    ps.setString(6, chunk.displayText());
                    ps.setString(7, embeddingInput);
                    ps.setString(8, chunk.headingPath());
                    ps.setInt(9, chunk.charCount());
                    ps.setInt(10, chunk.tokenCount());
                    ps.setString(11, chunk.contentHash());
                    if (chunk.startSourceIndex() == null) {
                        ps.setObject(12, null);
                    } else {
                        ps.setInt(12, chunk.startSourceIndex());
                    }
                    if (chunk.endSourceIndex() == null) {
                        ps.setObject(13, null);
                    } else {
                        ps.setInt(13, chunk.endSourceIndex());
                    }
                    ps.setString(14, request.cleaningReport().cleanerVersion());
                    ps.setString(15, chunkStrategyVersion); // NOSONAR: required for every batch row
                    ps.setString(16, embeddingModelName); // NOSONAR: required for every batch row
                    ps.setString(17, embeddingModelVersion); // NOSONAR: required for every batch row
                    ps.setString(18, request.chunkingResult().requestedStrategy().apiValue());
                    ps.setString(19, request.chunkingResult().appliedStrategy().apiValue());
                    ps.setString(20, metadataJson);
                    ps.setString(21, toVectorLiteral(embeddings.get(i)));
                }

                @Override
                public int getBatchSize() {
                    return batchChunks.size();
                }
            });
            callback.onProgress(new WriteProgress(end, total));
        }
        log.info("批量向量写入完成 fileId={} chunks={} dimension={} batchSize={}",
                request.fileId(), total, dimension, batchSize);
        return dimension;
    }

    private void validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("向量模型返回空 embedding");
        }
        if (expectedDimension > 0 && embedding.length != expectedDimension) {
            throw new IllegalStateException("embedding 维度不匹配，期望 "
                    + expectedDimension + " 实际 " + embedding.length);
        }
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("embedding 包含非有限数值");
            }
        }
    }

    private String toMetadataJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分块元数据序列化失败", e);
        }
    }

    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
