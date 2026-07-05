package com.example.myllm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "rag_call_log")
@Comment("RAG调用日志表")
public class RagCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_log_id", nullable = false)
    @Comment("关联交易日志ID")
    private TransactionLog transactionLog;

    @Column(nullable = false)
    @Comment("是否开启RAG")
    private Boolean ragEnabled;

    @Column(nullable = false)
    @Comment("是否执行RAG检索")
    private Boolean ragAttempted;

    @Column(nullable = false)
    @Comment("是否将检索结果用于提示词")
    private Boolean ragApplied;

    @Column(nullable = false)
    @Comment("检索命中分片数")
    private Integer retrievedChunks;

    @Lob
    @Column(columnDefinition = "TEXT")
    @Comment("检索限定文件ID（逗号分隔）")
    private String selectedFileIds;

    @Column(nullable = false, length = 24)
    @Comment("RAG状态（DISABLED/SUCCESS/FALLBACK）")
    private String ragStatus;

    @Lob
    @Column(columnDefinition = "TEXT")
    @Comment("RAG错误信息")
    private String ragError;

    @Lob
    @Column(columnDefinition = "TEXT")
    @Comment("规范化后的问题")
    private String normalizedQuery;

    @Lob
    @Column(columnDefinition = "TEXT")
    @Comment("改写后用于检索的问题")
    private String rewrittenQuery;

    @Column(length = 32)
    @Comment("问题改写策略（DISABLED/RULE_NORMALIZED/RULE_FILENAME_HINT）")
    private String rewriteStrategy;

    @Column(length = 24)
    @Comment("问题意图（FILENAME_LOOKUP/CONTENT_QA/HYBRID/SKIP_RAG）")
    private String queryIntent;

    @Column(length = 32)
    @Comment("实际检索模式（VECTOR/HYBRID/HYBRID_GRAPH_SHADOW 等）")
    private String retrievalMode;

    @Column
    @Comment("Dense向量召回数量")
    private Integer denseHitCount;

    @Column
    @Comment("BM25召回数量")
    private Integer bm25HitCount;

    @Column
    @Comment("RRF融合后的候选数量")
    private Integer fusedHitCount;

    @Column
    @Comment("BM25召回耗时（毫秒）")
    private Long bm25DurationMs;

    @Column
    @Comment("RRF融合耗时（毫秒）")
    private Long rrfDurationMs;

    @Lob
    @Column(columnDefinition = "TEXT")
    @Comment("BM25不可用时的降级原因")
    private String bm25FallbackReason;

    @Column
    @Comment("是否尝试图召回")
    private Boolean graphAttempted;

    @Column
    @Comment("图召回是否可用")
    private Boolean graphAvailable;

    @Column
    @Comment("图种子实体数量")
    private Integer graphSeedCount;

    @Column
    @Comment("图召回分片数量")
    private Integer graphHitCount;

    @Column
    @Comment("Shadow模式下与最终命中重合数")
    private Integer graphShadowOverlapCount;

    @Column
    @Comment("图召回耗时（毫秒）")
    private Long graphDurationMs;

    @Lob
    @Column(columnDefinition = "TEXT")
    @Comment("图召回降级原因")
    private String graphFallbackReason;

    @Column(nullable = false)
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

    public void setId(Long id) {
        this.id = id;
    }

    public TransactionLog getTransactionLog() {
        return transactionLog;
    }

    public void setTransactionLog(TransactionLog transactionLog) {
        this.transactionLog = transactionLog;
    }

    public Boolean getRagEnabled() {
        return ragEnabled;
    }

    public void setRagEnabled(Boolean ragEnabled) {
        this.ragEnabled = ragEnabled;
    }

    public Boolean getRagAttempted() {
        return ragAttempted;
    }

    public void setRagAttempted(Boolean ragAttempted) {
        this.ragAttempted = ragAttempted;
    }

    public Boolean getRagApplied() {
        return ragApplied;
    }

    public void setRagApplied(Boolean ragApplied) {
        this.ragApplied = ragApplied;
    }

    public Integer getRetrievedChunks() {
        return retrievedChunks;
    }

    public void setRetrievedChunks(Integer retrievedChunks) {
        this.retrievedChunks = retrievedChunks;
    }

    public String getSelectedFileIds() {
        return selectedFileIds;
    }

    public void setSelectedFileIds(String selectedFileIds) {
        this.selectedFileIds = selectedFileIds;
    }

    public String getRagStatus() {
        return ragStatus;
    }

    public void setRagStatus(String ragStatus) {
        this.ragStatus = ragStatus;
    }

    public String getRagError() {
        return ragError;
    }

    public void setRagError(String ragError) {
        this.ragError = ragError;
    }

    public String getNormalizedQuery() {
        return normalizedQuery;
    }

    public void setNormalizedQuery(String normalizedQuery) {
        this.normalizedQuery = normalizedQuery;
    }

    public String getRewrittenQuery() {
        return rewrittenQuery;
    }

    public void setRewrittenQuery(String rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    public String getRewriteStrategy() {
        return rewriteStrategy;
    }

    public void setRewriteStrategy(String rewriteStrategy) {
        this.rewriteStrategy = rewriteStrategy;
    }

    public String getQueryIntent() {
        return queryIntent;
    }

    public void setQueryIntent(String queryIntent) {
        this.queryIntent = queryIntent;
    }

    public String getRetrievalMode() {
        return retrievalMode;
    }

    public void setRetrievalMode(String retrievalMode) {
        this.retrievalMode = retrievalMode;
    }

    public Integer getDenseHitCount() {
        return denseHitCount;
    }

    public void setDenseHitCount(Integer denseHitCount) {
        this.denseHitCount = denseHitCount;
    }

    public Integer getBm25HitCount() {
        return bm25HitCount;
    }

    public void setBm25HitCount(Integer bm25HitCount) {
        this.bm25HitCount = bm25HitCount;
    }

    public Integer getFusedHitCount() {
        return fusedHitCount;
    }

    public void setFusedHitCount(Integer fusedHitCount) {
        this.fusedHitCount = fusedHitCount;
    }

    public Long getBm25DurationMs() {
        return bm25DurationMs;
    }

    public void setBm25DurationMs(Long bm25DurationMs) {
        this.bm25DurationMs = bm25DurationMs;
    }

    public Long getRrfDurationMs() {
        return rrfDurationMs;
    }

    public void setRrfDurationMs(Long rrfDurationMs) {
        this.rrfDurationMs = rrfDurationMs;
    }

    public String getBm25FallbackReason() {
        return bm25FallbackReason;
    }

    public void setBm25FallbackReason(String bm25FallbackReason) {
        this.bm25FallbackReason = bm25FallbackReason;
    }

    public Boolean getGraphAttempted() {
        return graphAttempted;
    }

    public void setGraphAttempted(Boolean graphAttempted) {
        this.graphAttempted = graphAttempted;
    }

    public Boolean getGraphAvailable() {
        return graphAvailable;
    }

    public void setGraphAvailable(Boolean graphAvailable) {
        this.graphAvailable = graphAvailable;
    }

    public Integer getGraphSeedCount() {
        return graphSeedCount;
    }

    public void setGraphSeedCount(Integer graphSeedCount) {
        this.graphSeedCount = graphSeedCount;
    }

    public Integer getGraphHitCount() {
        return graphHitCount;
    }

    public void setGraphHitCount(Integer graphHitCount) {
        this.graphHitCount = graphHitCount;
    }

    public Integer getGraphShadowOverlapCount() {
        return graphShadowOverlapCount;
    }

    public void setGraphShadowOverlapCount(Integer graphShadowOverlapCount) {
        this.graphShadowOverlapCount = graphShadowOverlapCount;
    }

    public Long getGraphDurationMs() {
        return graphDurationMs;
    }

    public void setGraphDurationMs(Long graphDurationMs) {
        this.graphDurationMs = graphDurationMs;
    }

    public String getGraphFallbackReason() {
        return graphFallbackReason;
    }

    public void setGraphFallbackReason(String graphFallbackReason) {
        this.graphFallbackReason = graphFallbackReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
