package com.example.myllm.service;

import com.example.myllm.repository.DocumentCleaningLogRepository;
import com.example.myllm.repository.DocumentStorageLogRepository;
import com.example.myllm.support.minio.MinioStorageService;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 跨 pgvector、MySQL 审计日志与 MinIO 的补偿清理。 */
@Service
public class FileIngestionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(FileIngestionCleanupService.class);
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[\\w&&[^\\d]]\\w*");

    private final JdbcTemplate vectorJdbcTemplate;
    private final DocumentCleaningLogRepository cleaningLogRepository;
    private final DocumentStorageLogRepository storageLogRepository;
    private final Optional<MinioStorageService> minioStorageService;
    private final String tableName;

    public FileIngestionCleanupService(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            DocumentCleaningLogRepository cleaningLogRepository,
            DocumentStorageLogRepository storageLogRepository,
            Optional<MinioStorageService> minioStorageService,
            @Value("${vector.table-name:file_embeddings}") String tableName) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.cleaningLogRepository = cleaningLogRepository;
        this.storageLogRepository = storageLogRepository;
        this.minioStorageService = minioStorageService;
        if (tableName == null || !SQL_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("vector.table-name 不是合法 SQL 标识符");
        }
        this.tableName = tableName;
    }

    public record CleanupResult(
            int deletedChunks,
            long deletedCleaningLogs,
            long deletedStorageLogs,
            int deletedMinioObjects) {}

    @Transactional
    @SuppressWarnings("java:S2077") // tableName is accepted only after strict SQL identifier validation.
    public CleanupResult cleanupByFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId 不能为空");
        }
        String normalizedFileId = fileId.trim();
        int deletedChunks = vectorJdbcTemplate.update( // NOSONAR: table name is strictly validated
                "DELETE FROM " + tableName + " WHERE file_id = ?", normalizedFileId);
        long deletedCleaningLogs = cleaningLogRepository.deleteByFileId(normalizedFileId);
        long deletedStorageLogs = 0;
        int deletedMinioObjects = 0;
        var storageLog = storageLogRepository.findByFileId(normalizedFileId);
        if (storageLog.isPresent()) {
            var storageEntry = storageLog.get();
            if (minioStorageService.isPresent()) {
                MinioStorageService storage = minioStorageService.get();
                deletedMinioObjects += storage.deleteObject(
                        storageEntry.getBucket(), storageEntry.getOriginalObjectPath());
                deletedMinioObjects += storage.deleteObject(
                        storageEntry.getBucket(), storageEntry.getParsedObjectPath());
            }
            storageLogRepository.delete(storageEntry);
            deletedStorageLogs = 1;
        }
        log.info("文件补偿清理完成 fileId={} chunks={} cleaningLogs={} storageLogs={} minioObjects={}",
                normalizedFileId, deletedChunks, deletedCleaningLogs, deletedStorageLogs, deletedMinioObjects);
        return new CleanupResult(deletedChunks, deletedCleaningLogs, deletedStorageLogs, deletedMinioObjects);
    }
}
