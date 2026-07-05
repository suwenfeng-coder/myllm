package com.example.myllm.harness.entity;

import com.example.myllm.harness.domain.ToolCallStatus;
import com.example.myllm.harness.domain.ToolRisk;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Comment;

/** Harness 工具调用审计记录。 */
@Entity
@Table(
        name = "harness_tool_call",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_harness_tool_idempotency",
                columnNames = {"tool_name", "idempotency_key"}),
        indexes = {
            @Index(name = "idx_harness_tool_run", columnList = "run_id"),
            @Index(name = "idx_harness_tool_step", columnList = "step_id")
        })
@Comment("Harness工具调用表")
public class HarnessToolCall {

    @Id
    @Column(name = "tool_call_id", nullable = false, length = 36)
    private String toolCallId;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "step_id", length = 36)
    private String stepId;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "tool_version", nullable = false, length = 16)
    private String toolVersion = "1";

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 24)
    private ToolRisk riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ToolCallStatus status;

    @Column(name = "arguments_redacted_json", columnDefinition = "TEXT")
    private String argumentsRedactedJson;

    @Column(name = "arguments_hash", nullable = false, length = 64)
    private String argumentsHash;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "result_preview", length = 2000)
    private String resultPreview;

    @Column(name = "result_artifact_id", length = 36)
    private String resultArtifactId;

    @Column(name = "result_hash", length = 64)
    private String resultHash;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "approval_id", length = 36)
    private String approvalId;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneId.systemDefault());
        }
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getStepId() {
        return stepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public void setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
    }

    public ToolRisk getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(ToolRisk riskLevel) {
        this.riskLevel = riskLevel;
    }

    public ToolCallStatus getStatus() {
        return status;
    }

    public void setStatus(ToolCallStatus status) {
        this.status = status;
    }

    public String getArgumentsRedactedJson() {
        return argumentsRedactedJson;
    }

    public void setArgumentsRedactedJson(String argumentsRedactedJson) {
        this.argumentsRedactedJson = argumentsRedactedJson;
    }

    public String getArgumentsHash() {
        return argumentsHash;
    }

    public void setArgumentsHash(String argumentsHash) {
        this.argumentsHash = argumentsHash;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getResultPreview() {
        return resultPreview;
    }

    public void setResultPreview(String resultPreview) {
        this.resultPreview = resultPreview;
    }

    public String getResultArtifactId() {
        return resultArtifactId;
    }

    public void setResultArtifactId(String resultArtifactId) {
        this.resultArtifactId = resultArtifactId;
    }

    public String getResultHash() {
        return resultHash;
    }

    public void setResultHash(String resultHash) {
        this.resultHash = resultHash;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
