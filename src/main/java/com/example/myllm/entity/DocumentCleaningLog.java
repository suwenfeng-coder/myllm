package com.example.myllm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Comment;

/**
 * 文档清理与分块审计日志。
 *
 * <p>保存解析后的清理前文本、清理后文本、字符/字节大小以及请求和实际分块策略。</p>
 */
@Entity
@Table(
        name = "document_cleaning_log",
        indexes = {
            @Index(name = "idx_cleaning_log_file_id", columnList = "file_id", unique = true),
            @Index(name = "idx_cleaning_log_created_at", columnList = "created_at")
        })
@Comment("文档数据清洗记录表")
public class DocumentCleaningLog {

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

    @Column(name = "cleaner_version", nullable = false, length = 64)
    @Comment("数据清洗规则版本")
    private String cleanerVersion;

    @Lob
    @Column(name = "raw_content", nullable = false, columnDefinition = "LONGTEXT")
    @Comment("文件解析后、数据清洗前的文本内容")
    private String rawContent;

    @Column(name = "raw_char_count", nullable = false)
    @Comment("清洗前Unicode字符数量")
    private Long rawCharCount;

    @Column(name = "raw_byte_size", nullable = false)
    @Comment("清洗前UTF-8字节数")
    private Long rawByteSize;

    @Lob
    @Column(name = "cleaned_content", nullable = false, columnDefinition = "LONGTEXT")
    @Comment("数据清洗后的文本内容")
    private String cleanedContent;

    @Column(name = "cleaned_char_count", nullable = false)
    @Comment("清洗后Unicode字符数量")
    private Long cleanedCharCount;

    @Column(name = "cleaned_byte_size", nullable = false)
    @Comment("清洗后UTF-8字节数")
    private Long cleanedByteSize;

    @Column(name = "removed_duplicate_blocks", nullable = false)
    @Comment("清洗时移除的完全重复文本块数量")
    private Integer removedDuplicateBlocks;

    @Column(name = "requested_chunk_strategy", length = 24)
    @Comment("用户请求的分块策略")
    private String requestedChunkStrategy;

    @Column(name = "applied_chunk_strategy", length = 24)
    @Comment("实际执行的分块策略，智能策略可能发生路由或降级")
    private String appliedChunkStrategy;

    @Column(name = "chunk_count")
    @Comment("清洗后生成的分块数量")
    private Integer chunkCount;

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

    public String getCleanerVersion() {
        return cleanerVersion;
    }

    public void setCleanerVersion(String cleanerVersion) {
        this.cleanerVersion = cleanerVersion;
    }

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public Long getRawCharCount() {
        return rawCharCount;
    }

    public void setRawCharCount(Long rawCharCount) {
        this.rawCharCount = rawCharCount;
    }

    public Long getRawByteSize() {
        return rawByteSize;
    }

    public void setRawByteSize(Long rawByteSize) {
        this.rawByteSize = rawByteSize;
    }

    public String getCleanedContent() {
        return cleanedContent;
    }

    public void setCleanedContent(String cleanedContent) {
        this.cleanedContent = cleanedContent;
    }

    public Long getCleanedCharCount() {
        return cleanedCharCount;
    }

    public void setCleanedCharCount(Long cleanedCharCount) {
        this.cleanedCharCount = cleanedCharCount;
    }

    public Long getCleanedByteSize() {
        return cleanedByteSize;
    }

    public void setCleanedByteSize(Long cleanedByteSize) {
        this.cleanedByteSize = cleanedByteSize;
    }

    public Integer getRemovedDuplicateBlocks() {
        return removedDuplicateBlocks;
    }

    public void setRemovedDuplicateBlocks(Integer removedDuplicateBlocks) {
        this.removedDuplicateBlocks = removedDuplicateBlocks;
    }

    public String getRequestedChunkStrategy() {
        return requestedChunkStrategy;
    }

    public void setRequestedChunkStrategy(String requestedChunkStrategy) {
        this.requestedChunkStrategy = requestedChunkStrategy;
    }

    public String getAppliedChunkStrategy() {
        return appliedChunkStrategy;
    }

    public void setAppliedChunkStrategy(String appliedChunkStrategy) {
        this.appliedChunkStrategy = appliedChunkStrategy;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
