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

/** 模型评测题目。 */
@Entity
@Table(
        name = "model_eval_question",
        indexes = {
            @Index(name = "idx_eval_question_suite", columnList = "suite_id,sort_order")
        })
@Comment("模型评测题目")
public class ModelEvalQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "suite_id", nullable = false)
    @Comment("所属题库 ID")
    private Long suiteId;

    @Column(name = "sort_order", nullable = false)
    @Comment("排序序号")
    private Integer sortOrder;

    @Column(name = "title", nullable = false, length = 256)
    @Comment("题目标题")
    private String title;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    @Comment("发给模型的题干")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 24)
    @Comment("题目类别")
    private EvalCategory category;

    @Column(name = "reference_answer", columnDefinition = "TEXT")
    @Comment("参考答案要点")
    private String referenceAnswer;

    @Column(name = "scoring_rubric", columnDefinition = "TEXT")
    @Comment("补充评分细则")
    private String scoringRubric;

    @Column(name = "output_format", length = 512)
    @Comment("期望输出格式说明")
    private String outputFormat;

    @Column(name = "enabled", nullable = false)
    @Comment("是否启用")
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        createdAt = now;
        updatedAt = now;
        if (enabled == null) {
            enabled = true;
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

    public Long getSuiteId() {
        return suiteId;
    }

    public void setSuiteId(Long suiteId) {
        this.suiteId = suiteId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
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

    public String getScoringRubric() {
        return scoringRubric;
    }

    public void setScoringRubric(String scoringRubric) {
        this.scoringRubric = scoringRubric;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
