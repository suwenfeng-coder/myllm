package com.example.myllm.eval.entity;

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
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Comment;

/** 单次评测中每道题的结果。 */
@Entity
@Table(
        name = "model_eval_item",
        indexes = {
            @Index(name = "idx_eval_item_run", columnList = "run_id,item_index"),
            @Index(name = "idx_eval_item_status", columnList = "status")
        })
@Comment("模型评测单题结果")
public class ModelEvalItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "question_id")
    @Comment("原题目 ID，题目删除后可为空")
    private Long questionId;

    @Column(name = "item_index", nullable = false)
    @Comment("题目序号")
    private Integer itemIndex;

    @Column(name = "title", nullable = false, length = 256)
    @Comment("题目标题快照")
    private String title;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    @Comment("题干快照")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 24)
    private EvalCategory category;

    @Column(name = "reference_answer", columnDefinition = "TEXT")
    private String referenceAnswer;

    @Column(name = "output_format", length = 512)
    private String outputFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EvalItemStatus status;

    @Column(name = "model_answer", columnDefinition = "LONGTEXT")
    private String modelAnswer;

    @Column(name = "answer_duration_ms")
    private Long answerDurationMs;

    @Column(name = "score_duration_ms")
    private Long scoreDurationMs;

    @Column(name = "understanding")
    private Integer understanding;

    @Column(name = "reasoning")
    private Integer reasoning;

    @Column(name = "code")
    private Integer code;

    @Column(name = "domain")
    private Integer domain;

    @Column(name = "stability")
    private Integer stability;

    @Column(name = "instruction")
    private Integer instruction;

    @Column(name = "item_score")
    @Comment("本题均分 0-5")
    private Double itemScore;

    @Column(name = "judge_summary", columnDefinition = "TEXT")
    private String judgeSummary;

    @Column(name = "judge_raw_json", columnDefinition = "TEXT")
    private String judgeRawJson;

    @Column(name = "manually_edited", nullable = false)
    @Comment("分数是否人工修改过")
    private Boolean manuallyEdited;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        createdAt = now;
        updatedAt = now;
        if (manuallyEdited == null) {
            manuallyEdited = false;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneId.systemDefault());
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Integer getItemIndex() {
        return itemIndex;
    }

    public void setItemIndex(Integer itemIndex) {
        this.itemIndex = itemIndex;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public EvalCategory getCategory() {
        return category;
    }

    public void setCategory(EvalCategory category) {
        this.category = category;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public EvalItemStatus getStatus() {
        return status;
    }

    public void setStatus(EvalItemStatus status) {
        this.status = status;
    }

    public String getModelAnswer() {
        return modelAnswer;
    }

    public void setModelAnswer(String modelAnswer) {
        this.modelAnswer = modelAnswer;
    }

    public Long getAnswerDurationMs() {
        return answerDurationMs;
    }

    public void setAnswerDurationMs(Long answerDurationMs) {
        this.answerDurationMs = answerDurationMs;
    }

    public Long getScoreDurationMs() {
        return scoreDurationMs;
    }

    public void setScoreDurationMs(Long scoreDurationMs) {
        this.scoreDurationMs = scoreDurationMs;
    }

    public Integer getUnderstanding() {
        return understanding;
    }

    public void setUnderstanding(Integer understanding) {
        this.understanding = understanding;
    }

    public Integer getReasoning() {
        return reasoning;
    }

    public void setReasoning(Integer reasoning) {
        this.reasoning = reasoning;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public Integer getDomain() {
        return domain;
    }

    public void setDomain(Integer domain) {
        this.domain = domain;
    }

    public Integer getStability() {
        return stability;
    }

    public void setStability(Integer stability) {
        this.stability = stability;
    }

    public Integer getInstruction() {
        return instruction;
    }

    public void setInstruction(Integer instruction) {
        this.instruction = instruction;
    }

    public Double getItemScore() {
        return itemScore;
    }

    public void setItemScore(Double itemScore) {
        this.itemScore = itemScore;
    }

    public String getJudgeSummary() {
        return judgeSummary;
    }

    public void setJudgeSummary(String judgeSummary) {
        this.judgeSummary = judgeSummary;
    }

    public String getJudgeRawJson() {
        return judgeRawJson;
    }

    public void setJudgeRawJson(String judgeRawJson) {
        this.judgeRawJson = judgeRawJson;
    }

    public Boolean getManuallyEdited() {
        return manuallyEdited;
    }

    public void setManuallyEdited(Boolean manuallyEdited) {
        this.manuallyEdited = manuallyEdited;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
