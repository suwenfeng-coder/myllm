package com.example.myllm.harness.entity;

import com.example.myllm.harness.domain.ApprovalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Comment;

/** Harness 人工审批记录。 */
@Entity
@Table(
        name = "harness_approval",
        indexes = {
            @Index(name = "idx_harness_approval_run", columnList = "run_id"),
            @Index(name = "idx_harness_approval_status", columnList = "status,expires_at")
        })
@Comment("Harness人工审批表")
public class HarnessApproval {

    @Id
    @Column(name = "approval_id", nullable = false, length = 36)
    private String approvalId;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "tool_call_id", nullable = false, length = 36)
    private String toolCallId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ApprovalStatus status;

    @Column(name = "risk_summary", length = 1000)
    private String riskSummary;

    @Column(name = "requested_action", nullable = false, length = 128)
    private String requestedAction;

    @Column(name = "arguments_redacted_json", columnDefinition = "TEXT")
    private String argumentsRedactedJson;

    @Column(name = "arguments_hash", nullable = false, length = 64)
    private String argumentsHash;

    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decision_comment", length = 2000)
    private String decisionComment;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneId.systemDefault());
        }
    }

    public String getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public void setStatus(ApprovalStatus status) {
        this.status = status;
    }

    public String getRiskSummary() {
        return riskSummary;
    }

    public void setRiskSummary(String riskSummary) {
        this.riskSummary = riskSummary;
    }

    public String getRequestedAction() {
        return requestedAction;
    }

    public void setRequestedAction(String requestedAction) {
        this.requestedAction = requestedAction;
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

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public void setDecisionComment(String decisionComment) {
        this.decisionComment = decisionComment;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
