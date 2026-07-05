package com.example.myllm.eval.entity;

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

/** 一次模型评测运行。 */
@Entity
@Table(
        name = "model_eval_run",
        indexes = {
            @Index(name = "idx_eval_run_status_created", columnList = "status,created_at"),
            @Index(name = "idx_eval_run_suite", columnList = "suite_id")
        })
@Comment("模型评测运行")
public class ModelEvalRun {

    @Id
    @Column(name = "run_id", nullable = false, length = 36)
    @Comment("运行 UUID")
    private String runId;

    @Column(name = "suite_id", nullable = false)
    @Comment("题库 ID")
    private Long suiteId;

    @Column(name = "suite_name", nullable = false, length = 256)
    @Comment("运行时题库名称快照")
    private String suiteName;

    @Column(name = "parent_run_id", length = 36)
    @Comment("上一轮评测 runId（继续评测时）")
    private String parentRunId;

    @Column(name = "model_name", nullable = false, length = 128)
    @Comment("被测模型")
    private String modelName;

    @Column(name = "judge_model_name", nullable = false, length = 128)
    @Comment("裁判模型")
    private String judgeModelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Comment("运行状态")
    private EvalRunStatus status;

    @Column(name = "total_questions", nullable = false)
    @Comment("总题数")
    private Integer totalQuestions;

    @Column(name = "completed_questions", nullable = false)
    @Comment("已完成题数")
    private Integer completedQuestions;

    @Column(name = "total_score")
    @Comment("总分均值 0-5")
    private Double totalScore;

    @Column(name = "dimension_scores_json", columnDefinition = "TEXT")
    @Comment("各维度均分 JSON")
    private String dimensionScoresJson;

    @Column(name = "notes", columnDefinition = "TEXT")
    @Comment("备注")
    private String notes;

    @Column(name = "error_message", columnDefinition = "TEXT")
    @Comment("失败原因")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        createdAt = now;
        updatedAt = now;
        if (completedQuestions == null) {
            completedQuestions = 0;
        }
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

    public Long getSuiteId() {
        return suiteId;
    }

    public void setSuiteId(Long suiteId) {
        this.suiteId = suiteId;
    }

    public String getSuiteName() {
        return suiteName;
    }

    public void setSuiteName(String suiteName) {
        this.suiteName = suiteName;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public void setParentRunId(String parentRunId) {
        this.parentRunId = parentRunId;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getJudgeModelName() {
        return judgeModelName;
    }

    public void setJudgeModelName(String judgeModelName) {
        this.judgeModelName = judgeModelName;
    }

    public EvalRunStatus getStatus() {
        return status;
    }

    public void setStatus(EvalRunStatus status) {
        this.status = status;
    }

    public Integer getTotalQuestions() {
        return totalQuestions;
    }

    public void setTotalQuestions(Integer totalQuestions) {
        this.totalQuestions = totalQuestions;
    }

    public Integer getCompletedQuestions() {
        return completedQuestions;
    }

    public void setCompletedQuestions(Integer completedQuestions) {
        this.completedQuestions = completedQuestions;
    }

    public Double getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(Double totalScore) {
        this.totalScore = totalScore;
    }

    public String getDimensionScoresJson() {
        return dimensionScoresJson;
    }

    public void setDimensionScoresJson(String dimensionScoresJson) {
        this.dimensionScoresJson = dimensionScoresJson;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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
