package com.example.myllm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "transaction_log")
@Comment("大模型对话交易日志表")
public class TransactionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Column(nullable = false)
    @Comment("请求时间")
    private LocalDateTime createdAt;

    @Column(nullable = false, length = 32)
    @Comment("模型提供商")
    private String provider;

    @Column(nullable = false, length = 128)
    @Comment("调用的模型名称")
    private String model;

    @Column(nullable = false)
    @Comment("端到端耗时（毫秒）")
    private Long durationMs;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    @Comment("用户输入内容")
    private String inputContent;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    @Comment("模型输出内容")
    private String outputContent;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    @Comment("系统提示词")
    private String systemPrompt;

    @Column
    @Comment("输入Token数")
    private Long inputTokens;

    @Column
    @Comment("输出Token数")
    private Long outputTokens;

    @Column
    @Comment("总Token消耗量")
    private Long totalTokens;

    @Column
    @Comment("推理速度（tokens/秒）")
    private Double inferenceSpeedTps;

    @Column
    @Comment("Prompt编码耗时（毫秒）")
    private Long promptEvalDurationMs;

    @Column
    @Comment("模型生成耗时（毫秒）")
    private Long evalDurationMs;

    @Column
    @Comment("模型加载耗时（毫秒）")
    private Long loadDurationMs;

    @Column
    @Comment("Ollama报告的总推理耗时（毫秒）")
    private Long modelTotalDurationMs;

    @Column(nullable = false, length = 16)
    @Comment("请求状态（SUCCESS/FAILED）")
    private String status;

    @Lob
    @Column(columnDefinition = "TEXT")
    @Comment("失败错误信息")
    private String errorMessage;

    @Column(nullable = false, length = 16)
    @Comment("请求类型（POST/GET）")
    private String requestType;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneId.systemDefault());
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getInputContent() {
        return inputContent;
    }

    public void setInputContent(String inputContent) {
        this.inputContent = inputContent;
    }

    public String getOutputContent() {
        return outputContent;
    }

    public void setOutputContent(String outputContent) {
        this.outputContent = outputContent;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Double getInferenceSpeedTps() {
        return inferenceSpeedTps;
    }

    public void setInferenceSpeedTps(Double inferenceSpeedTps) {
        this.inferenceSpeedTps = inferenceSpeedTps;
    }

    public Long getPromptEvalDurationMs() {
        return promptEvalDurationMs;
    }

    public void setPromptEvalDurationMs(Long promptEvalDurationMs) {
        this.promptEvalDurationMs = promptEvalDurationMs;
    }

    public Long getEvalDurationMs() {
        return evalDurationMs;
    }

    public void setEvalDurationMs(Long evalDurationMs) {
        this.evalDurationMs = evalDurationMs;
    }

    public Long getLoadDurationMs() {
        return loadDurationMs;
    }

    public void setLoadDurationMs(Long loadDurationMs) {
        this.loadDurationMs = loadDurationMs;
    }

    public Long getModelTotalDurationMs() {
        return modelTotalDurationMs;
    }

    public void setModelTotalDurationMs(Long modelTotalDurationMs) {
        this.modelTotalDurationMs = modelTotalDurationMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getRequestType() {
        return requestType;
    }

    public void setRequestType(String requestType) {
        this.requestType = requestType;
    }
}
