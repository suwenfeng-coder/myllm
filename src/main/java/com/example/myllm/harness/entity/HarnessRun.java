package com.example.myllm.harness.entity;

import com.example.myllm.harness.domain.RunStatus;
import com.example.myllm.harness.domain.RunType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Comment;

/** Harness 运行主记录，含 lease 与预算计数。 */
@Entity
@Table(
        name = "harness_run",
        indexes = {
            @Index(name = "idx_harness_run_status_created", columnList = "status,created_at"),
            @Index(name = "idx_harness_run_definition", columnList = "definition_id,definition_version"),
            @Index(name = "idx_harness_run_trace", columnList = "trace_id"),
            @Index(name = "idx_harness_run_lease", columnList = "status,lease_expires_at")
        })
@Comment("Harness运行主表")
public class HarnessRun {

    @Id
    @Column(name = "run_id", nullable = false, length = 36)
    @Comment("运行UUID")
    private String runId;

    @Column(name = "definition_id", nullable = false, length = 64)
    private String definitionId;

    @Column(name = "definition_version", nullable = false)
    private int definitionVersion;

    @Column(name = "definition_hash", nullable = false, length = 64)
    private String definitionHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 32)
    private RunType runType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RunStatus status;

    @Column(name = "objective", columnDefinition = "TEXT")
    private String objective;

    @Column(name = "conversation_id", length = 36)
    private String conversationId;

    @Column(name = "requester_id", length = 64)
    private String requesterId;

    @Column(name = "client_request_id", length = 128, unique = true)
    private String clientRequestId;

    @Column(name = "transaction_log_id")
    private Long transactionLogId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Column(name = "max_steps", nullable = false)
    private int maxSteps;

    @Column(name = "model_call_count", nullable = false)
    private int modelCallCount;

    @Column(name = "tool_call_count", nullable = false)
    private int toolCallCount;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_token", length = 36)
    private String leaseToken;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "final_output_preview", length = 2000)
    private String finalOutputPreview;

    @Column(name = "final_artifact_id", length = 36)
    private String finalArtifactId;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneId.systemDefault());
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public void setDefinitionId(String definitionId) {
        this.definitionId = definitionId;
    }

    public int getDefinitionVersion() {
        return definitionVersion;
    }

    public void setDefinitionVersion(int definitionVersion) {
        this.definitionVersion = definitionVersion;
    }

    public String getDefinitionHash() {
        return definitionHash;
    }

    public void setDefinitionHash(String definitionHash) {
        this.definitionHash = definitionHash;
    }

    public RunType getRunType() {
        return runType;
    }

    public void setRunType(RunType runType) {
        this.runType = runType;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public String getObjective() {
        return objective;
    }

    public void setObjective(String objective) {
        this.objective = objective;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRequesterId() {
        return requesterId;
    }

    public void setRequesterId(String requesterId) {
        this.requesterId = requesterId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public Long getTransactionLogId() {
        return transactionLogId;
    }

    public void setTransactionLogId(Long transactionLogId) {
        this.transactionLogId = transactionLogId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(int currentStep) {
        this.currentStep = currentStep;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public int getModelCallCount() {
        return modelCallCount;
    }

    public void setModelCallCount(int modelCallCount) {
        this.modelCallCount = modelCallCount;
    }

    public int getToolCallCount() {
        return toolCallCount;
    }

    public void setToolCallCount(int toolCallCount) {
        this.toolCallCount = toolCallCount;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(String leaseToken) {
        this.leaseToken = leaseToken;
    }

    public LocalDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public String getFinalOutputPreview() {
        return finalOutputPreview;
    }

    public void setFinalOutputPreview(String finalOutputPreview) {
        this.finalOutputPreview = finalOutputPreview;
    }

    public String getFinalArtifactId() {
        return finalArtifactId;
    }

    public void setFinalArtifactId(String finalArtifactId) {
        this.finalArtifactId = finalArtifactId;
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

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
