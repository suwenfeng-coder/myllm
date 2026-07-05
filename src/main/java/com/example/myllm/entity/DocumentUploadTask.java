package com.example.myllm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Comment;

/**
 * 异步文档上传与向量化任务。
 *
 * <p>向量入库成功后可选触发 Neo4j 构图任务（独立 worker），上传任务本身不等待图索引完成。</p>
 */
@Entity
@Table(
        name = "document_upload_task",
        indexes = {
            @Index(name = "idx_upload_task_status_created", columnList = "status,created_at"),
            @Index(name = "idx_upload_task_file_id", columnList = "file_id")
        })
@Comment("文档上传向量化异步任务表")
public class DocumentUploadTask {

    @Id
    @Column(name = "task_id", nullable = false, length = 36)
    @Comment("任务UUID")
    private String taskId;

    @Column(name = "file_id", length = 64)
    @Comment("生成的向量文件ID")
    private String fileId;

    @Column(name = "file_name", nullable = false, length = 512)
    @Comment("原始文件名")
    private String fileName;

    @Column(name = "temp_file_path", nullable = false, length = 1024)
    @Comment("临时文件路径")
    private String tempFilePath;

    @Column(name = "content_type", length = 128)
    @Comment("上传Content-Type")
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    @Comment("文件大小字节")
    private Long fileSizeBytes;

    @Column(name = "chunk_strategy", nullable = false, length = 24)
    @Comment("分块策略")
    private String chunkStrategy;

    @Column(name = "parse_mode", nullable = false, length = 16)
    @Comment("解析模式")
    private String parseMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Comment("任务状态")
    private UploadTaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 24)
    @Comment("当前阶段")
    private UploadTaskPhase phase;

    @Column(name = "percent", nullable = false)
    @Comment("整体进度0-100")
    private Integer percent;

    @Column(name = "message", length = 512)
    @Comment("进度说明")
    private String message;

    @Column(name = "eta_seconds")
    @Comment("预计剩余秒数")
    private Long etaSeconds;

    @Column(name = "docforge_job_id", length = 64)
    @Comment("DocForge异步解析任务ID")
    private String docforgeJobId;

    @Column(name = "graph_index_enqueued", nullable = false)
    @Comment("是否已入队Neo4j构图")
    private Boolean graphIndexEnqueued;

    @Column(name = "result_json", columnDefinition = "TEXT")
    @Comment("成功结果JSON")
    private String resultJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    @Comment("失败原因")
    private String errorMessage;

    @Column(name = "started_at")
    @Comment("开始执行时间")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    @Comment("结束时间")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("更新时间")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (percent == null) {
            percent = 0;
        }
        if (graphIndexEnqueued == null) {
            graphIndexEnqueued = false;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneId.systemDefault());
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
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

    public String getTempFilePath() {
        return tempFilePath;
    }

    public void setTempFilePath(String tempFilePath) {
        this.tempFilePath = tempFilePath;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getChunkStrategy() {
        return chunkStrategy;
    }

    public void setChunkStrategy(String chunkStrategy) {
        this.chunkStrategy = chunkStrategy;
    }

    public String getParseMode() {
        return parseMode;
    }

    public void setParseMode(String parseMode) {
        this.parseMode = parseMode;
    }

    public UploadTaskStatus getStatus() {
        return status;
    }

    public void setStatus(UploadTaskStatus status) {
        this.status = status;
    }

    public UploadTaskPhase getPhase() {
        return phase;
    }

    public void setPhase(UploadTaskPhase phase) {
        this.phase = phase;
    }

    public Integer getPercent() {
        return percent;
    }

    public void setPercent(Integer percent) {
        this.percent = percent;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getEtaSeconds() {
        return etaSeconds;
    }

    public void setEtaSeconds(Long etaSeconds) {
        this.etaSeconds = etaSeconds;
    }

    public String getDocforgeJobId() {
        return docforgeJobId;
    }

    public void setDocforgeJobId(String docforgeJobId) {
        this.docforgeJobId = docforgeJobId;
    }

    public Boolean getGraphIndexEnqueued() {
        return graphIndexEnqueued;
    }

    public void setGraphIndexEnqueued(Boolean graphIndexEnqueued) {
        this.graphIndexEnqueued = graphIndexEnqueued;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
