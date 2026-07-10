package com.example.myllm.service;

import com.example.myllm.config.MinioProperties;
import com.example.myllm.entity.DocumentStorageLog;
import com.example.myllm.repository.DocumentStorageLogRepository;
import com.example.myllm.support.document.DocumentTextRenderer;
import com.example.myllm.support.document.DocumentParseResult;
import com.example.myllm.support.document.ParsedDocument;
import com.example.myllm.support.minio.MinioStorageService;
import com.example.myllm.support.minio.MinioStoredObject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageService.class);

    private final DocumentStorageLogRepository repository;
    private final Optional<MinioStorageService> minioStorageService;
    private final MinioProperties minioProperties;

    public DocumentStorageService(
            DocumentStorageLogRepository repository,
            Optional<MinioStorageService> minioStorageService,
            MinioProperties minioProperties) {
        this.repository = repository;
        this.minioStorageService = minioStorageService;
        this.minioProperties = minioProperties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DocumentStorageLog store(
            String fileId,
            String fileName,
            MultipartFile file,
            ParsedDocument parsedDocument,
            DocumentParseResult parseResult) {
        if (!minioProperties.enabled()) {
            throw new IllegalStateException("MinIO 存储未启用，请在配置中设置 minio.enabled=true");
        }
        MinioStorageService storageService = minioStorageService.orElseThrow(() ->
                new IllegalStateException("MinIO 存储服务未初始化，请检查 MinIO 配置"));

        LocalDate storageDate = LocalDate.now(ZoneId.systemDefault());
        String parsedContent = DocumentTextRenderer.renderRaw(parsedDocument.blocks());
        MinioStoredObject originalObject = storageService.storeOriginal(fileId, file, storageDate);
        MinioStoredObject parsedObject = storageService.storeParsed(
                fileId, fileName, parsedContent, storageDate);

        DocumentStorageLog storageLog = new DocumentStorageLog();
        storageLog.setFileId(fileId.trim());
        storageLog.setFileName(fileName == null || fileName.isBlank() ? "unknown" : fileName);
        storageLog.setBucket(originalObject.bucket());
        storageLog.setOriginalObjectPath(originalObject.objectPath());
        storageLog.setOriginalFileName(originalObject.fileName());
        storageLog.setParsedObjectPath(parsedObject.objectPath());
        storageLog.setParsedFileName(parsedObject.fileName());
        storageLog.setRequestedParseMode(parseResult.requestedParseMode());
        storageLog.setParseMode(parseResult.parseMode());
        storageLog.setParserEngine(parseResult.parserEngine());
        storageLog.setAttemptedParseModes(String.join(",", parseResult.attemptedModes()));
        storageLog.setParseDurationMs(parseResult.parseDurationMs());
        storageLog.setParsePages(parseResult.parsedPages());
        storageLog.setParseFallbackReason(parseResult.fallbackReason());
        storageLog.setOriginalSizeBytes(originalObject.sizeBytes());
        storageLog.setParsedSizeBytes(parsedObject.sizeBytes());

        DocumentStorageLog saved = repository.save(storageLog);
        log.info("文档存储记录已保存 id={} fileId={} originalPath={} parsedPath={}",
                saved.getId(), saved.getFileId(), saved.getOriginalObjectPath(), saved.getParsedObjectPath());
        return saved;
    }

    public boolean isEnabled() {
        return minioProperties.enabled() && minioStorageService.isPresent();
    }
}
