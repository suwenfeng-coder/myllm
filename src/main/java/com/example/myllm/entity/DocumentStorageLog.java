package com.example.myllm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Comment;

@Entity
@Table(
        name = "document_storage_log",
        indexes = {
            @Index(name = "idx_storage_log_file_id", columnList = "file_id", unique = true),
            @Index(name = "idx_storage_log_created_at", columnList = "created_at")
        })
@Comment("文档 MinIO 存储记录表")
public class DocumentStorageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Column(name = "file_id", nullable = false, length = 64)
    @Comment("关联向量文件ID")
    private String fileId;

    @Column(name = "file_name", nullable = false, length = 512)
    @Comment("原始文件名")
    private String fileName;

    @Column(name = "bucket", nullable = false, length = 128)
    @Comment("MinIO bucket 名称")
    private String bucket;

    @Column(name = "original_object_path", nullable = false, length = 1024)
    @Comment("原始文件在 MinIO 中的对象路径")
    private String originalObjectPath;

    @Column(name = "original_file_name", nullable = false, length = 512)
    @Comment("原始文件保存名")
    private String originalFileName;

    @Column(name = "parsed_object_path", nullable = false, length = 1024)
    @Comment("解析结果在 MinIO 中的对象路径")
    private String parsedObjectPath;

    @Column(name = "parsed_file_name", nullable = false, length = 512)
    @Comment("解析结果保存名")
    private String parsedFileName;

    @Column(name = "parse_mode", nullable = false, length = 16)
    @Comment("实际解析模式 local/docling/maker")
    private String parseMode;

    @Column(name = "requested_parse_mode", length = 16)
    @Comment("请求解析模式 auto/local/docling/maker")
    private String requestedParseMode;

    @Column(name = "parser_engine", length = 32)
    @Comment("解析服务报告的实际引擎")
    private String parserEngine;

    @Column(name = "attempted_parse_modes", length = 128)
    @Comment("自动模式依次尝试的解析器")
    private String attemptedParseModes;

    @Column(name = "parse_duration_ms")
    @Comment("解析链路总耗时（毫秒）")
    private Long parseDurationMs;

    @Column(name = "parse_pages")
    @Comment("解析页数")
    private Integer parsePages;

    @Column(name = "parse_fallback_reason", columnDefinition = "TEXT")
    @Comment("自动模式或引擎内部降级原因")
    private String parseFallbackReason;

    @Column(name = "original_size_bytes", nullable = false)
    @Comment("原始文件字节数")
    private Long originalSizeBytes;

    @Column(name = "parsed_size_bytes", nullable = false)
    @Comment("解析文件字节数")
    private Long parsedSizeBytes;

    @Column(name = "created_at", nullable = false)
    @Comment("记录创建时间")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneId.systemDefault());
        }
    }

    public Long getId() {
        return id;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getOriginalObjectPath() {
        return originalObjectPath;
    }

    public void setOriginalObjectPath(String originalObjectPath) {
        this.originalObjectPath = originalObjectPath;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getParsedObjectPath() {
        return parsedObjectPath;
    }

    public void setParsedObjectPath(String parsedObjectPath) {
        this.parsedObjectPath = parsedObjectPath;
    }

    public String getParsedFileName() {
        return parsedFileName;
    }

    public void setParsedFileName(String parsedFileName) {
        this.parsedFileName = parsedFileName;
    }

    public String getParseMode() {
        return parseMode;
    }

    public void setParseMode(String parseMode) {
        this.parseMode = parseMode;
    }

    public String getRequestedParseMode() {
        return requestedParseMode;
    }

    public void setRequestedParseMode(String requestedParseMode) {
        this.requestedParseMode = requestedParseMode;
    }

    public String getParserEngine() {
        return parserEngine;
    }

    public void setParserEngine(String parserEngine) {
        this.parserEngine = parserEngine;
    }

    public String getAttemptedParseModes() {
        return attemptedParseModes;
    }

    public void setAttemptedParseModes(String attemptedParseModes) {
        this.attemptedParseModes = attemptedParseModes;
    }

    public Long getParseDurationMs() {
        return parseDurationMs;
    }

    public void setParseDurationMs(Long parseDurationMs) {
        this.parseDurationMs = parseDurationMs;
    }

    public Integer getParsePages() {
        return parsePages;
    }

    public void setParsePages(Integer parsePages) {
        this.parsePages = parsePages;
    }

    public String getParseFallbackReason() {
        return parseFallbackReason;
    }

    public void setParseFallbackReason(String parseFallbackReason) {
        this.parseFallbackReason = parseFallbackReason;
    }

    public Long getOriginalSizeBytes() {
        return originalSizeBytes;
    }

    public void setOriginalSizeBytes(Long originalSizeBytes) {
        this.originalSizeBytes = originalSizeBytes;
    }

    public Long getParsedSizeBytes() {
        return parsedSizeBytes;
    }

    public void setParsedSizeBytes(Long parsedSizeBytes) {
        this.parsedSizeBytes = parsedSizeBytes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
