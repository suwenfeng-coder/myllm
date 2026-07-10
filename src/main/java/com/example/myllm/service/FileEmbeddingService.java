package com.example.myllm.service;

import com.example.myllm.dto.FileEmbeddingResponse;
import com.example.myllm.dto.IngestPipelineTiming;
import com.example.myllm.dto.VectorChunkResult;
import com.example.myllm.dto.VectorFileSummary;
import com.example.myllm.dto.VectorSearchResponse;
import com.example.myllm.entity.DocumentStorageLog;
import com.example.myllm.support.document.CleanedDocument;
import com.example.myllm.support.document.CleaningReport;
import com.example.myllm.config.GraphProperties;
import com.example.myllm.entity.UploadTaskPhase;
import com.example.myllm.entity.UploadTaskStatus;
import com.example.myllm.support.document.DocumentParseResult;
import com.example.myllm.support.document.ParseProgressListener;
import com.example.myllm.support.upload.UploadProgressReporter;
import com.example.myllm.support.document.ParsedDocument;
import com.example.myllm.support.chunking.ChunkingResult;
import com.example.myllm.support.chunking.DocumentChunk;
import com.example.myllm.support.retrieval.QueryFilenameMatcher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档入库与向量检索的主业务门面。
 *
 * <p>入库侧串联解析、MinIO 存储、数据清洗、分块、批量 embedding、pgvector 写入和可选 Neo4j 构图任务；
 * 检索侧提供 Dense 向量召回、文件列表、按文件名线索解析 fileId、删除补偿和旧版 RAG Prompt 组装。</p>
 *
 * <p>当前类仍承担较多职责，是后续拆分候选：解析/清洗/分块/写入可沉淀为 IngestionPipeline，自动 DDL
 * 可迁移到 VectorSchemaManager，Prompt 组装可迁移到独立 RagPromptAssembler。</p>
 */
@Service
public class FileEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(FileEmbeddingService.class);
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[\\w&&[^\\d]]\\w*");
    private static final String COLUMN_FILE_ID = "file_id";
    private static final String COLUMN_FILE_NAME = "file_name";
    private static final String COLUMN_HEADING_PATH = "heading_path";
    private static final String COLUMN_CHAR_COUNT = "char_count";
    private static final String COLUMN_TOKEN_COUNT = "token_count";
    private static final String COLUMN_CONTENT_HASH = "content_hash";
    private static final String COLUMN_METADATA = "metadata";
    private static final String TYPE_VARCHAR_64 = "VARCHAR(64)";
    private static final String ALTER_TABLE = "ALTER TABLE ";

    private final JdbcTemplate vectorJdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final DocumentCleaningService documentCleaningService;
    private final DocumentCleaningLogService documentCleaningLogService;
    private final DocumentChunkingService documentChunkingService;
    private final DocumentParseService documentParseService;
    private final DocumentStorageService documentStorageService;
    private final GraphIndexTaskService graphIndexTaskService;
    private final GraphProperties graphProperties;
    private final VectorChunkBatchWriter vectorChunkBatchWriter;
    private final FileIngestionCleanupService fileIngestionCleanupService;
    private final ObjectMapper objectMapper;
    private final String tableName;
    private final int defaultTopK;
    private final boolean hnswEnabled;
    private final int hnswM;
    private final int hnswEfConstruction;

    public FileEmbeddingService(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            EmbeddingModel embeddingModel,
            DocumentCleaningService documentCleaningService,
            DocumentCleaningLogService documentCleaningLogService,
            DocumentChunkingService documentChunkingService,
            DocumentParseService documentParseService,
            DocumentStorageService documentStorageService,
            GraphIndexTaskService graphIndexTaskService,
            GraphProperties graphProperties,
            VectorChunkBatchWriter vectorChunkBatchWriter,
            FileIngestionCleanupService fileIngestionCleanupService,
            ObjectMapper objectMapper,
            @Value("${vector.table-name:file_embeddings}") String tableName,
            @Value("${vector.search-top-k:5}") int defaultTopK,
            @Value("${vector.index.hnsw-enabled:true}") boolean hnswEnabled,
            @Value("${vector.index.hnsw-m:16}") int hnswM,
            @Value("${vector.index.hnsw-ef-construction:64}") int hnswEfConstruction) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.documentCleaningService = documentCleaningService;
        this.documentCleaningLogService = documentCleaningLogService;
        this.documentChunkingService = documentChunkingService;
        this.documentParseService = documentParseService;
        this.documentStorageService = documentStorageService;
        this.graphIndexTaskService = graphIndexTaskService;
        this.graphProperties = graphProperties;
        this.vectorChunkBatchWriter = vectorChunkBatchWriter;
        this.fileIngestionCleanupService = fileIngestionCleanupService;
        this.objectMapper = objectMapper;
        this.tableName = requireSqlIdentifier(tableName, "vector.table-name");
        this.defaultTopK = defaultTopK;
        this.hnswEnabled = hnswEnabled;
        this.hnswM = Math.max(4, hnswM);
        this.hnswEfConstruction = Math.max(16, hnswEfConstruction);
    }

    public FileEmbeddingResponse embedAndStore(MultipartFile file) {
        return embedAndStore(file, "fixed");
    }

    public FileEmbeddingResponse embedAndStore(MultipartFile file, String requestedChunkStrategy) {
        return embedAndStore(file, requestedChunkStrategy, "auto");
    }

    /**
     * 完成文件解析、清理、指定策略分块、审计记录和向量入库。
     *
     * @param file 上传文件
     * @param requestedChunkStrategy fixed/recursive/document/semantic/smart
     * @param parseMode auto/local/docling/maker（marker 仅为兼容别名）
     * @return 包含清理、分块和向量维度摘要的响应
     */
    public FileEmbeddingResponse embedAndStore(
            MultipartFile file, String requestedChunkStrategy, String parseMode) {
        return embedAndStoreWithProgress(file, requestedChunkStrategy, parseMode, UploadProgressReporter.noop())
                .response();
    }

    /**
     * 分阶段执行解析、清理、分块与向量入库，并通过 reporter 上报进度。
     *
     * <p>该方法是同步上传与异步上传 Worker 共用的真实入库流水线。它先生成本次入库的 {@code fileId}，
     * 只有 pgvector 写入成功后才持久化清洗审计；若 embedding 或写库失败，会按 fileId 触发 best-effort
     * 补偿清理，避免半成品分片参与后续检索。</p>
     *
     * <p>Neo4j 构图是派生能力：构图任务创建失败只记录告警，不回滚已经成功的向量入库。</p>
     *
     * @return 向量化结果及是否已入队 Neo4j 构图任务
     */
    @SuppressWarnings("java:S3776") // Pipeline stages intentionally keep compensation state in one scope.
    public EmbedPipelineOutcome embedAndStoreWithProgress(
            MultipartFile file,
            String requestedChunkStrategy,
            String parseMode,
            UploadProgressReporter reporter) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        ensureVectorTableExists();

        long pipelineStartNanos = System.nanoTime();
        String fileId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        reporter.report(
                UploadTaskStatus.RUNNING,
                UploadTaskPhase.PARSING,
                5,
                "开始解析文档",
                null,
                null);
        ParseProgressListener parseListener = (subPercent, message, etaSeconds, docforgeJobId) -> {
            int overall = 5 + Math.min(50, Math.max(0, subPercent) * 50 / 100);
            reporter.report(
                    UploadTaskStatus.RUNNING,
                    UploadTaskPhase.PARSING,
                    overall,
                    message == null || message.isBlank() ? "解析中" : message,
                    etaSeconds,
                    docforgeJobId);
        };
        DocumentParseResult parseResult = documentParseService.parse(file, parseMode, parseListener);
        ParsedDocument parsedDocument = parseResult.document();
        DocumentStorageLog storageLog = null;
        Long storingDurationMs = null;
        if (documentStorageService.isEnabled()) {
            reporter.report(
                    UploadTaskStatus.RUNNING,
                    UploadTaskPhase.STORING,
                    56,
                    "保存原始与解析结果",
                    null,
                    null);
            long storingStartNanos = System.nanoTime();
            storageLog = documentStorageService.store(fileId, fileName, file, parsedDocument, parseResult);
            storingDurationMs = elapsedMillis(storingStartNanos);
        }
        reporter.report(
                UploadTaskStatus.RUNNING,
                UploadTaskPhase.CLEANING,
                62,
                "清理文档内容",
                null,
                null);
        String contentType = stringMetadata(parsedDocument.metadata(), "extension");
        if (contentType == null || contentType.isBlank()) {
            contentType = "unknown";
        }
        long cleaningStartNanos = System.nanoTime();
        CleanedDocument cleanedDocument = documentCleaningService.clean(parsedDocument);
        long cleaningDurationMs = elapsedMillis(cleaningStartNanos);
        CleaningReport cleaningReport = cleanedDocument.report();
        reporter.report(
                UploadTaskStatus.RUNNING,
                UploadTaskPhase.CHUNKING,
                72,
                "文档分块",
                null,
                null);
        long chunkingStartNanos = System.nanoTime();
        ChunkingResult chunkingResult = documentChunkingService.chunk(cleanedDocument, requestedChunkStrategy);
        long chunkingDurationMs = elapsedMillis(chunkingStartNanos);
        List<DocumentChunk> chunks = chunkingResult.chunks();

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文件内容为空，无法向量化");
        }

        long fileSizeBytes = file.getSize();
        int totalChunks = chunks.size();
        int embeddingDimension;
        long embeddingDurationMs;
        try {
            reporter.report(
                    UploadTaskStatus.RUNNING,
                    UploadTaskPhase.EMBEDDING,
                    80,
                    "向量化中 (0/" + totalChunks + ")",
                    null,
                    null);
            VectorChunkBatchWriter.WriteRequest writeRequest = new VectorChunkBatchWriter.WriteRequest(
                    fileId,
                    fileName,
                    contentType,
                    fileSizeBytes,
                    cleaningReport,
                    chunkingResult,
                    parsedDocument.metadata());
            long embeddingStartNanos = System.nanoTime();
            embeddingDimension = vectorChunkBatchWriter.writeAll(writeRequest, chunks, progress -> {
                int embedPercent = 80 + (progress.embedded() * 18 / Math.max(1, progress.total()));
                reporter.report(
                        UploadTaskStatus.RUNNING,
                        UploadTaskPhase.EMBEDDING,
                        embedPercent,
                        "向量化中 (" + progress.embedded() + "/" + progress.total() + ")",
                        null,
                        null);
            });
            embeddingDurationMs = elapsedMillis(embeddingStartNanos);
            documentCleaningLogService.save(fileId, fileName, cleanedDocument, chunkingResult);
        } catch (Exception e) {
            fileIngestionCleanupService.cleanupByFileId(fileId);
            throw e;
        }

        String headingSample = chunks.stream()
                .map(DocumentChunk::headingPath)
                .filter(path -> path != null && !path.isBlank())
                .findFirst()
                .orElse("(none)");
        boolean graphIndexEnqueued = enqueueGraphIndex(fileId, fileName);
        String completionMessage = graphIndexEnqueued
                ? "文件已完成向量化；Neo4j 构图任务已入队"
                : "文件已完成向量化并写入 pgvector";
        reporter.report(
                UploadTaskStatus.RUNNING,
                UploadTaskPhase.EMBEDDING,
                99,
                "收尾处理",
                0L,
                null);
        long pipelineDurationMs = elapsedMillis(pipelineStartNanos);
        IngestPipelineTiming pipelineTiming = new IngestPipelineTiming(
                storingDurationMs,
                cleaningDurationMs,
                chunkingDurationMs,
                embeddingDurationMs,
                pipelineDurationMs);
        if (log.isInfoEnabled()) {
            log.info("文件清理及向量化完成 fileName={} fileId={} parseMode={} parserEngine={} contentType={} cleanerVersion={} requestedStrategy={} appliedStrategy={} rawChars={} cleanedChars={} removalRatio={} duplicateBlocks={} chunks={} dimension={} headingSample={} parseDurationMs={} storingMs={} cleaningMs={} chunkingMs={} embeddingMs={} pipelineMs={} warnings={} graphEnqueued={}",
                    fileName, fileId, parseResult.parseMode(), parseResult.parserEngine(), contentType,
                    cleaningReport.cleanerVersion(), chunkingResult.requestedStrategy().apiValue(),
                    chunkingResult.appliedStrategy().apiValue(), cleaningReport.rawCharCount(),
                    cleaningReport.cleanedCharCount(), String.format("%.3f", cleaningReport.removalRatio()),
                    cleaningReport.removedDuplicateBlocks(), chunks.size(), embeddingDimension, headingSample,
                    parseResult.parseDurationMs(), storingDurationMs, cleaningDurationMs, chunkingDurationMs,
                    embeddingDurationMs, pipelineDurationMs, cleaningReport.warnings(), graphIndexEnqueued);
        }
        FileEmbeddingResponse response = new FileEmbeddingResponse(
                fileId,
                fileName,
                chunks.size(),
                embeddingDimension,
                cleaningReport.cleanerVersion(),
                cleaningReport.rawCharCount(),
                cleaningReport.cleanedCharCount(),
                cleaningReport.removedDuplicateBlocks(),
                chunkingResult.requestedStrategy().apiValue(),
                chunkingResult.appliedStrategy().apiValue(),
                completionMessage,
                parseResult.requestedParseMode(),
                parseResult.parseMode(),
                parseResult.parserEngine(),
                parseResult.parsedPages(),
                parseResult.parsedPages(),
                parseResult.parseDurationMs(),
                parseResult.attemptedModes(),
                parseResult.fallbackReason(),
                storageLog == null ? null : storageLog.getOriginalObjectPath(),
                storageLog == null ? null : storageLog.getParsedObjectPath(),
                storageLog == null ? null : storageLog.getOriginalFileName(),
                storageLog == null ? null : storageLog.getParsedFileName(),
                pipelineTiming);
        return new EmbedPipelineOutcome(response, graphIndexEnqueued, fileId);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public record EmbedPipelineOutcome(
            FileEmbeddingResponse response, boolean graphIndexEnqueued, String fileId) {
    }

    public VectorSearchResponse search(String query, Integer topK) {
        return search(query, topK, List.of());
    }

    /**
     * 执行 Dense 向量召回。
     *
     * <p>这里不做 RAG 最终准入判断，只返回按 pgvector 距离排序的候选分片。阈值过滤、文件名加权、
     * BM25/Graph 融合和单文件限额由 {@link RagRetrievalService} 统一处理。</p>
     *
     * <p>当 {@code fileIds} 非空时只在用户指定范围内检索；SQL 表名只允许配置为合法标识符，避免动态表名
     * 注入风险。</p>
     */
    public VectorSearchResponse search(String query, Integer topK, List<String> fileIds) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }

        ensureVectorTableExists();

        int limit = topK == null || topK <= 0 ? defaultTopK : topK;
        float[] queryEmbedding = embeddingModel.embed(query.trim());
        if (queryEmbedding.length == 0) {
            throw new IllegalStateException("向量模型返回空 embedding");
        }

        String vectorLiteral = toVectorLiteral(queryEmbedding);
        List<String> normalizedFileIds = fileIds == null ? List.of() : fileIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .toList();

        StringBuilder sql = new StringBuilder("SELECT file_id, file_name, chunk_index, chunk_text, heading_path,")
                .append(" COALESCE(char_count, 0) AS char_count, token_count, content_hash, metadata,")
                .append(" 1 - (embedding <=> ?::vector) AS similarity")
                .append(" FROM ").append(tableName);
        List<Object> params = new ArrayList<>();
        params.add(vectorLiteral);

        if (!normalizedFileIds.isEmpty()) {
            sql.append(" WHERE file_id IN (");
            for (int i = 0; i < normalizedFileIds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                params.add(normalizedFileIds.get(i));
            }
            sql.append(")");
        }
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        params.add(vectorLiteral);
        params.add(limit);

        List<VectorChunkResult> results = vectorJdbcTemplate.query( // NOSONAR: table name is strictly validated
                sql.toString(),
                (rs, rowNum) -> new VectorChunkResult(
                        rs.getString(COLUMN_FILE_ID),
                        rs.getString(COLUMN_FILE_NAME),
                        rs.getInt("chunk_index"),
                        rs.getString("chunk_text"),
                        rs.getDouble("similarity"),
                        rs.getString(COLUMN_HEADING_PATH),
                        rs.getInt(COLUMN_CHAR_COUNT),
                        (Integer) rs.getObject(COLUMN_TOKEN_COUNT),
                        rs.getString(COLUMN_CONTENT_HASH),
                        parseMetadata(rs.getString(COLUMN_METADATA))),
                params.toArray());

        log.info("向量检索完成 queryLen={} topK={} fileFilterCount={} hits={}",
                query.length(), limit, normalizedFileIds.size(), results.size());
        return new VectorSearchResponse(query.trim(), limit, results);
    }

    /**
     * 按 fileId + chunkIndex 批量回源 PostgreSQL 分片正文。
     */
    public List<VectorChunkResult> fetchChunksByKeys(List<ChunkKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        ensureVectorTableExists();
        List<ChunkKey> normalized = keys.stream()
                .filter(key -> key != null && key.fileId() != null && !key.fileId().isBlank())
                .map(key -> new ChunkKey(key.fileId().trim(), key.chunkIndex()))
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("SELECT file_id, file_name, chunk_index, chunk_text, heading_path,")
                .append(" COALESCE(char_count, 0) AS char_count, token_count, content_hash, metadata")
                .append(" FROM ").append(tableName)
                .append(" WHERE ");
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("(file_id = ? AND chunk_index = ?)");
            params.add(normalized.get(i).fileId());
            params.add(normalized.get(i).chunkIndex());
        }
        sql.append(" ORDER BY chunk_index");

        return vectorJdbcTemplate.query( // NOSONAR: table name is strictly validated
                sql.toString(),
                (rs, rowNum) -> new VectorChunkResult(
                        rs.getString(COLUMN_FILE_ID),
                        rs.getString(COLUMN_FILE_NAME),
                        rs.getInt("chunk_index"),
                        rs.getString("chunk_text"),
                        0.0,
                        rs.getString(COLUMN_HEADING_PATH),
                        rs.getInt(COLUMN_CHAR_COUNT),
                        (Integer) rs.getObject(COLUMN_TOKEN_COUNT),
                        rs.getString(COLUMN_CONTENT_HASH),
                        parseMetadata(rs.getString(COLUMN_METADATA))),
                params.toArray());
    }

    public record ChunkKey(String fileId, int chunkIndex) {
    }

    public List<VectorFileSummary> listFiles() {
        ensureVectorTableExists();
        String sql = "SELECT file_id, MIN(file_name) AS file_name, MIN(content_type) AS content_type,"
                + " COUNT(*) AS chunk_count, MAX(created_at) AS created_at"
                + " FROM " + tableName
                + " GROUP BY file_id"
                + " ORDER BY MAX(created_at) DESC";
        return vectorJdbcTemplate.query(sql, (rs, rowNum) -> new VectorFileSummary( // NOSONAR
                rs.getString(COLUMN_FILE_ID),
                rs.getString(COLUMN_FILE_NAME),
                rs.getString("content_type"),
                rs.getInt("chunk_count"),
                rs.getTimestamp("created_at").toLocalDateTime()));
    }

    /**
     * 根据问题中提取出的文件名线索解析实际文件 ID。
     *
     * <p>优先返回规范化后完全相等的文件名；仅当没有完全匹配时才使用包含匹配。
     * 若页面已指定文件范围，只会在该范围内解析，绝不越过用户选择。</p>
     *
     * @param filenameHints 查询中的书名或文件名
     * @param selectedFileIds 页面显式选择的文件范围
     * @return 匹配到的文件 ID；无匹配时为空列表
     */
    public List<String> resolveFileIdsByFilenameHints(
            List<String> filenameHints,
            List<String> selectedFileIds) {
        if (filenameHints == null || filenameHints.isEmpty()) {
            return List.of();
        }
        List<String> normalizedHints = filenameHints.stream()
                .map(QueryFilenameMatcher::normalize)
                .filter(hint -> !hint.isBlank())
                .distinct()
                .toList();
        if (normalizedHints.isEmpty()) {
            return List.of();
        }
        List<String> selected = selectedFileIds == null ? List.of() : selectedFileIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();

        List<VectorFileSummary> candidates = listFiles().stream()
                .filter(file -> selected.isEmpty() || selected.contains(file.fileId()))
                .toList();
        List<String> exactMatches = candidates.stream()
                .filter(file -> normalizedHints.contains(QueryFilenameMatcher.normalize(file.fileName())))
                .map(VectorFileSummary::fileId)
                .distinct()
                .toList();
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }
        return candidates.stream()
                .filter(file -> {
                    String normalizedFileName = QueryFilenameMatcher.normalize(file.fileName());
                    return normalizedHints.stream().anyMatch(hint ->
                            normalizedFileName.contains(hint) || hint.contains(normalizedFileName));
                })
                .map(VectorFileSummary::fileId)
                .distinct()
                .toList();
    }

    @SuppressWarnings("java:S2077") // tableName is accepted only after strict SQL identifier validation.
    public FileIngestionCleanupService.CleanupResult deleteByFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId 不能为空");
        }
        ensureVectorTableExists();
        String normalizedFileId = fileId.trim();
        String fileName = vectorJdbcTemplate.query( // NOSONAR: table name is strictly validated
                        "SELECT file_name FROM " + tableName + " WHERE file_id = ? ORDER BY chunk_index LIMIT 1",
                        (rs, rowNum) -> rs.getString(COLUMN_FILE_NAME),
                        normalizedFileId)
                .stream()
                .findFirst()
                .orElse(normalizedFileId);
        FileIngestionCleanupService.CleanupResult result =
                fileIngestionCleanupService.cleanupByFileId(normalizedFileId);
        enqueueGraphDelete(normalizedFileId, fileName);
        return result;
    }

    private boolean enqueueGraphIndex(String fileId, String fileName) {
        if (!graphProperties.isEnabled()) {
            return false;
        }
        try {
            graphIndexTaskService.enqueueIndex(fileId, fileName);
            return true;
        } catch (Exception e) {
            log.warn("创建 Neo4j 构图任务失败，不回滚已完成的向量入库 fileId={} error={}",
                    fileId, e.getMessage(), e);
            return false;
        }
    }

    private void enqueueGraphDelete(String fileId, String fileName) {
        try {
            graphIndexTaskService.enqueueDelete(fileId, fileName);
        } catch (Exception e) {
            log.warn("创建 Neo4j 图删除任务失败，不回滚已完成的向量删除 fileId={} error={}",
                    fileId, e.getMessage(), e);
        }
    }

    /**
     * 将已经通过准入的分片组装成当前在线 ChatService 使用的 RAG Prompt。
     *
     * <p>这是兼容现有 `/api/chat` 的轻量实现：它保留文件名、chunkIndex 和章节路径，便于用户理解来源。
     * 更严格的 Token 预算、不可信证据边界和引用白名单已在 Harness Context 中实现，后续可回灌到在线链路。</p>
     */
    public String buildRagPrompt(String question, List<VectorChunkResult> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return question;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("请优先根据以下参考资料回答问题；若资料仅部分相关，可结合相关资料与通用知识作答。\n\n参考资料：\n");
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunkResult chunk = chunks.get(i);
            sb.append('[').append(i + 1).append("] 文件: ")
                    .append(chunk.fileName())
                    .append(" (片段 ")
                    .append(chunk.chunkIndex())
                    .append(')');
            if (chunk.headingPath() != null && !chunk.headingPath().isBlank()) {
                sb.append(" | 章节: ").append(chunk.headingPath());
            }
            sb.append('\n').append(chunk.chunkText()).append("\n\n");
        }
        sb.append("问题：").append(question);
        return sb.toString();
    }

    /**
     * 确保 Demo/本地环境所需的 pgvector 表结构和索引存在。
     *
     * <p>这是为了降低本地试用门槛而保留的兼容 DDL。生产化后应迁移到可审计的版本化迁移脚本，并把
     * {@code embedding vector} 固定为与模型版本匹配的物理维度。</p>
     */
    private void ensureVectorTableExists() {
        vectorJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        vectorJdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (" // NOSONAR
                + "id BIGSERIAL PRIMARY KEY,"
                + "file_id VARCHAR(64) NOT NULL,"
                + "file_name TEXT NOT NULL,"
                + "chunk_index INT NOT NULL,"
                + "chunk_text TEXT NOT NULL,"
                + "requested_chunk_strategy VARCHAR(24),"
                + "applied_chunk_strategy VARCHAR(24),"
                + "embedding vector NOT NULL,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")");
        vectorJdbcTemplate.execute(ALTER_TABLE + tableName // NOSONAR
                + " ADD COLUMN IF NOT EXISTS requested_chunk_strategy VARCHAR(24)");
        vectorJdbcTemplate.execute(ALTER_TABLE + tableName // NOSONAR
                + " ADD COLUMN IF NOT EXISTS applied_chunk_strategy VARCHAR(24)");
        addColumnIfMissing("content_type", "VARCHAR(16)");
        addColumnIfMissing("file_size_bytes", "BIGINT");
        addColumnIfMissing("embedding_content", "TEXT");
        addColumnIfMissing(COLUMN_HEADING_PATH, "TEXT");
        addColumnIfMissing(COLUMN_CHAR_COUNT, "INT");
        addColumnIfMissing(COLUMN_TOKEN_COUNT, "INT");
        addColumnIfMissing(COLUMN_CONTENT_HASH, "CHAR(64)");
        addColumnIfMissing("start_source_index", "INT");
        addColumnIfMissing("end_source_index", "INT");
        addColumnIfMissing("cleaner_version", TYPE_VARCHAR_64);
        addColumnIfMissing("chunk_strategy_version", TYPE_VARCHAR_64);
        addColumnIfMissing("embedding_model", TYPE_VARCHAR_64);
        addColumnIfMissing("embedding_model_version", "VARCHAR(32)");
        addColumnIfMissing(COLUMN_METADATA, "JSONB DEFAULT '{}'::jsonb");
        vectorJdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_" + tableName + "_file_chunk" // NOSONAR
                + " ON " + tableName + " (file_id, chunk_index)");
        vectorJdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_file_id" // NOSONAR
                + " ON " + tableName + " (file_id)");
        vectorJdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_content_hash" // NOSONAR
                + " ON " + tableName + " (content_hash)");
        backfillEmbeddingContent();
        ensureHnswIndex();
    }

    private void ensureHnswIndex() {
        if (!hnswEnabled) {
            return;
        }
        String indexName = "idx_" + tableName + "_embedding_hnsw";
        try {
            vectorJdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + indexName // NOSONAR
                    + " ON " + tableName + " USING hnsw (embedding vector_cosine_ops)"
                    + " WITH (m = " + hnswM + ", ef_construction = " + hnswEfConstruction + ")");
            log.info("HNSW 向量索引已就绪 index={} m={} ef_construction={}",
                    indexName, hnswM, hnswEfConstruction);
        } catch (Exception e) {
            log.warn("HNSW 索引创建失败，将回退顺序扫描: {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(String column, String definition) {
        vectorJdbcTemplate.execute(ALTER_TABLE + tableName // NOSONAR
                + " ADD COLUMN IF NOT EXISTS " + column + " " + definition);
    }

    private void backfillEmbeddingContent() {
        vectorJdbcTemplate.update("UPDATE " + tableName // NOSONAR
                + " SET embedding_content = chunk_text"
                + " WHERE embedding_content IS NULL AND chunk_text IS NOT NULL");
        vectorJdbcTemplate.update("UPDATE " + tableName // NOSONAR
                + " SET char_count = char_length(chunk_text)"
                + " WHERE char_count IS NULL AND chunk_text IS NOT NULL");
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("分块元数据反序列化失败 json={}", json, e);
            return Map.of();
        }
    }

    private static String requireSqlIdentifier(String value, String propertyName) {
        if (value == null || !SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(propertyName + " 不是合法 SQL 标识符");
        }
        return value;
    }

    private static String stringMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private static String toVectorLiteral(float[] vector) {
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
