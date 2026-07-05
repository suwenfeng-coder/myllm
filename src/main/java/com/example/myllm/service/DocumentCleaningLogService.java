package com.example.myllm.service;

import com.example.myllm.entity.DocumentCleaningLog;
import com.example.myllm.repository.DocumentCleaningLogRepository;
import com.example.myllm.support.document.CleanedDocument;
import com.example.myllm.support.document.CleaningReport;
import com.example.myllm.support.chunking.ChunkingResult;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将清理前后内容、大小和分块策略写入 MySQL 审计日志。
 */
@Service
public class DocumentCleaningLogService {

    private static final Logger log = LoggerFactory.getLogger(DocumentCleaningLogService.class);

    private final DocumentCleaningLogRepository repository;

    public DocumentCleaningLogService(DocumentCleaningLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 在独立事务中保存清理和分块结果，确保后续向量化失败时仍可追踪处理记录。
     *
     * @param fileId 与 pgvector 文件记录关联的唯一标识
     * @param fileName 原始文件名
     * @param document 清理前后内容及清理报告
     * @param chunkingResult 请求策略、实际策略和分块数量
     * @return 已持久化的日志实体
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DocumentCleaningLog save(
            String fileId, String fileName, CleanedDocument document, ChunkingResult chunkingResult) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId 不能为空");
        }
        if (document == null || document.report() == null) {
            throw new IllegalArgumentException("清洗结果不能为空");
        }
        if (chunkingResult == null) {
            throw new IllegalArgumentException("分块结果不能为空");
        }

        CleaningReport report = document.report();
        DocumentCleaningLog logEntry = new DocumentCleaningLog();
        logEntry.setFileId(fileId.trim());
        logEntry.setFileName(fileName == null || fileName.isBlank() ? "unknown" : fileName);
        logEntry.setCleanerVersion(report.cleanerVersion());
        logEntry.setRawContent(document.rawContent());
        logEntry.setRawCharCount((long) document.rawContent().codePointCount(0, document.rawContent().length()));
        logEntry.setRawByteSize((long) document.rawContent().getBytes(StandardCharsets.UTF_8).length);
        logEntry.setCleanedContent(document.content());
        logEntry.setCleanedCharCount((long) document.content().codePointCount(0, document.content().length()));
        logEntry.setCleanedByteSize((long) document.content().getBytes(StandardCharsets.UTF_8).length);
        logEntry.setRemovedDuplicateBlocks(report.removedDuplicateBlocks());
        logEntry.setRequestedChunkStrategy(chunkingResult.requestedStrategy().apiValue());
        logEntry.setAppliedChunkStrategy(chunkingResult.appliedStrategy().apiValue());
        logEntry.setChunkCount(chunkingResult.chunks().size());

        DocumentCleaningLog saved = repository.save(logEntry);
        log.info("数据清洗记录已保存 id={} fileId={} rawChars={} rawBytes={} cleanedChars={} cleanedBytes={} requestedStrategy={} appliedStrategy={} chunks={}",
                saved.getId(), saved.getFileId(), saved.getRawCharCount(), saved.getRawByteSize(),
                saved.getCleanedCharCount(), saved.getCleanedByteSize(),
                saved.getRequestedChunkStrategy(), saved.getAppliedChunkStrategy(), saved.getChunkCount());
        return saved;
    }
}
