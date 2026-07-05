package com.example.myllm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 异步上传任务与临时文件配置。 */
@ConfigurationProperties(prefix = "upload.task")
public class UploadTaskProperties {

    private boolean enabled = true;
    private String tempDir = ".runtime/uploads";
    private long pollIntervalMs = 2000;
    private long runningTimeoutMs = 7_200_000;
    private long pendingTimeoutMs = 3_600_000;
    private long asyncThresholdBytes = 1_048_576;
    private long retentionHours = 72;
    private long cleanupIntervalMs = 3_600_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTempDir() {
        return tempDir;
    }

    public void setTempDir(String tempDir) {
        this.tempDir = tempDir;
    }

    public long getPollIntervalMs() {
        return Math.max(500, pollIntervalMs);
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getRunningTimeoutMs() {
        return Math.max(60_000, runningTimeoutMs);
    }

    public void setRunningTimeoutMs(long runningTimeoutMs) {
        this.runningTimeoutMs = runningTimeoutMs;
    }

    public long getAsyncThresholdBytes() {
        return Math.max(0, asyncThresholdBytes);
    }

    public void setAsyncThresholdBytes(long asyncThresholdBytes) {
        this.asyncThresholdBytes = asyncThresholdBytes;
    }

    public long getPendingTimeoutMs() {
        return Math.max(60_000, pendingTimeoutMs);
    }

    public void setPendingTimeoutMs(long pendingTimeoutMs) {
        this.pendingTimeoutMs = pendingTimeoutMs;
    }

    public long getRetentionHours() {
        return Math.max(1, retentionHours);
    }

    public void setRetentionHours(long retentionHours) {
        this.retentionHours = retentionHours;
    }

    public long getCleanupIntervalMs() {
        return Math.max(60_000, cleanupIntervalMs);
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }
}
