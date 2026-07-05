package com.example.myllm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Neo4j 图索引和图召回配置。
 *
 * <p>图能力默认关闭，并将连接、构图、召回分别设置开关，确保 Neo4j 故障不会影响
 * 现有 Dense + BM25 主链路。</p>
 */
@ConfigurationProperties(prefix = "graph")
public class GraphProperties {

    private boolean enabled;
    private String database = "neo4j";
    private boolean healthCheckOnStartup = true;
    private boolean failFast;
    private final Indexing indexing = new Indexing();
    private final Retrieval retrieval = new Retrieval();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public boolean isHealthCheckOnStartup() {
        return healthCheckOnStartup;
    }

    public void setHealthCheckOnStartup(boolean healthCheckOnStartup) {
        this.healthCheckOnStartup = healthCheckOnStartup;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public Indexing getIndexing() {
        return indexing;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    /** 构图任务、批次和重试配置。 */
    public static class Indexing {

        private boolean enabled;
        private boolean schemaInitializationEnabled = true;
        private int schemaAwaitSeconds = 60;
        private int batchSize = 200;
        private long taskPollIntervalMs = 5000;
        private int maxAttempts = 5;
        private long retryInitialDelayMs = 5000;
        private long retryMaxDelayMs = 300000;
        private long runningTimeoutMs = 600000;
        private String extractionVersion = "structure-v1";
        private boolean entityExtractionEnabled;
        private String entityExtractionVersion = "entity-v1";
        private int entityExtractionBatchSize = 3;
        private int entityExtractionMaxChunks = 500;
        private double minConfidence = 0.75;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isSchemaInitializationEnabled() {
            return schemaInitializationEnabled;
        }

        public void setSchemaInitializationEnabled(boolean schemaInitializationEnabled) {
            this.schemaInitializationEnabled = schemaInitializationEnabled;
        }

        public int getSchemaAwaitSeconds() {
            return Math.max(1, schemaAwaitSeconds);
        }

        public void setSchemaAwaitSeconds(int schemaAwaitSeconds) {
            this.schemaAwaitSeconds = schemaAwaitSeconds;
        }

        public int getBatchSize() {
            return Math.max(1, batchSize);
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getTaskPollIntervalMs() {
            return Math.max(1000, taskPollIntervalMs);
        }

        public void setTaskPollIntervalMs(long taskPollIntervalMs) {
            this.taskPollIntervalMs = taskPollIntervalMs;
        }

        public int getMaxAttempts() {
            return Math.max(1, maxAttempts);
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getRetryInitialDelayMs() {
            return Math.max(1000, retryInitialDelayMs);
        }

        public void setRetryInitialDelayMs(long retryInitialDelayMs) {
            this.retryInitialDelayMs = retryInitialDelayMs;
        }

        public long getRetryMaxDelayMs() {
            return Math.max(getRetryInitialDelayMs(), retryMaxDelayMs);
        }

        public void setRetryMaxDelayMs(long retryMaxDelayMs) {
            this.retryMaxDelayMs = retryMaxDelayMs;
        }

        public long getRunningTimeoutMs() {
            return Math.max(60000, runningTimeoutMs);
        }

        public void setRunningTimeoutMs(long runningTimeoutMs) {
            this.runningTimeoutMs = runningTimeoutMs;
        }

        public String getExtractionVersion() {
            return extractionVersion;
        }

        public void setExtractionVersion(String extractionVersion) {
            this.extractionVersion = extractionVersion;
        }

        public boolean isEntityExtractionEnabled() {
            return entityExtractionEnabled;
        }

        public void setEntityExtractionEnabled(boolean entityExtractionEnabled) {
            this.entityExtractionEnabled = entityExtractionEnabled;
        }

        public String getEntityExtractionVersion() {
            return entityExtractionVersion;
        }

        public void setEntityExtractionVersion(String entityExtractionVersion) {
            this.entityExtractionVersion = entityExtractionVersion;
        }

        public int getEntityExtractionBatchSize() {
            return Math.max(1, Math.min(8, entityExtractionBatchSize));
        }

        public void setEntityExtractionBatchSize(int entityExtractionBatchSize) {
            this.entityExtractionBatchSize = entityExtractionBatchSize;
        }

        public int getEntityExtractionMaxChunks() {
            return Math.max(1, entityExtractionMaxChunks);
        }

        public void setEntityExtractionMaxChunks(int entityExtractionMaxChunks) {
            this.entityExtractionMaxChunks = entityExtractionMaxChunks;
        }

        public double getMinConfidence() {
            return Math.max(0.0, Math.min(1.0, minConfidence));
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }
    }

    /** Graph RAG 灰度和查询边界配置；当前阶段保持关闭。 */
    public static class Retrieval {

        private boolean enabled;
        private boolean shadowMode = true;
        private int seedTopK = 10;
        private int candidateTopK = 30;
        private int maxHops = 2;
        private long timeoutMs = 3000;
        private double minScore = 0.20;
        private double rrfWeight = 0.80;
        private String fulltextIndexName = "entity_name_fulltext";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isShadowMode() {
            return shadowMode;
        }

        public void setShadowMode(boolean shadowMode) {
            this.shadowMode = shadowMode;
        }

        public int getSeedTopK() {
            return Math.max(1, seedTopK);
        }

        public void setSeedTopK(int seedTopK) {
            this.seedTopK = seedTopK;
        }

        public int getCandidateTopK() {
            return Math.max(1, candidateTopK);
        }

        public void setCandidateTopK(int candidateTopK) {
            this.candidateTopK = candidateTopK;
        }

        public int getMaxHops() {
            return Math.max(1, Math.min(2, maxHops));
        }

        public void setMaxHops(int maxHops) {
            this.maxHops = maxHops;
        }

        public long getTimeoutMs() {
            return Math.max(100, timeoutMs);
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public double getMinScore() {
            return Math.max(0.0, Math.min(1.0, minScore));
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public double getRrfWeight() {
            return Math.max(0.0, rrfWeight);
        }

        public void setRrfWeight(double rrfWeight) {
            this.rrfWeight = rrfWeight;
        }

        public String getFulltextIndexName() {
            return fulltextIndexName;
        }

        public void setFulltextIndexName(String fulltextIndexName) {
            this.fulltextIndexName = fulltextIndexName;
        }
    }
}
