package com.example.myllm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Comment;

/**
 * Neo4j 文档构图持久化任务。
 *
 * <p>任务以文件、抽取版本和操作类型保证幂等；Neo4j 暂时不可用时保留任务并重试，
 * 不回滚已完成的文档向量化。</p>
 */
@Entity
@Table(
        name = "document_graph_index_task",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_graph_task_file_version_operation",
                columnNames = {"file_id", "extraction_version", "operation"}),
        indexes = {
            @Index(name = "idx_graph_task_status_retry", columnList = "status,next_retry_at"),
            @Index(name = "idx_graph_task_file_id", columnList = "file_id")
        })
@Comment("Neo4j文档构图任务表")
public class DocumentGraphIndexTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("任务主键ID")
    private Long id;

    @Column(name = "file_id", nullable = false, length = 64)
    @Comment("关联向量文件ID")
    private String fileId;

    @Column(name = "file_name", nullable = false, length = 512)
    @Comment("原始文件名")
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 16)
    @Comment("任务操作类型INDEX或DELETE")
    private GraphIndexTaskOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Comment("任务状态")
    private GraphIndexTaskStatus status;

    @Column(name = "extraction_version", nullable = false, length = 64)
    @Comment("图结构或知识抽取版本")
    private String extractionVersion;

    @Column(name = "attempt_count", nullable = false)
    @Comment("已执行次数")
    private Integer attemptCount;

    @Column(name = "node_count", nullable = false)
    @Comment("最近成功生成的节点数量")
    private Integer nodeCount;

    @Column(name = "relationship_count", nullable = false)
    @Comment("最近成功生成的关系数量")
    private Integer relationshipCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    @Comment("最近失败原因")
    private String errorMessage;

    @Column(name = "next_retry_at")
    @Comment("下次允许重试时间")
    private LocalDateTime nextRetryAt;

    @Column(name = "started_at")
    @Comment("最近开始执行时间")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    @Comment("最终完成或进入死信时间")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false)
    @Comment("任务创建时间")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("任务更新时间")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (attemptCount == null) {
            attemptCount = 0;
        }
        if (nodeCount == null) {
            nodeCount = 0;
        }
        if (relationshipCount == null) {
            relationshipCount = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneId.systemDefault());
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

    public GraphIndexTaskOperation getOperation() {
        return operation;
    }

    public void setOperation(GraphIndexTaskOperation operation) {
        this.operation = operation;
    }

    public GraphIndexTaskStatus getStatus() {
        return status;
    }

    public void setStatus(GraphIndexTaskStatus status) {
        this.status = status;
    }

    public String getExtractionVersion() {
        return extractionVersion;
    }

    public void setExtractionVersion(String extractionVersion) {
        this.extractionVersion = extractionVersion;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Integer getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(Integer nodeCount) {
        this.nodeCount = nodeCount;
    }

    public Integer getRelationshipCount() {
        return relationshipCount;
    }

    public void setRelationshipCount(Integer relationshipCount) {
        this.relationshipCount = relationshipCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
