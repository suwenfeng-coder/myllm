package com.example.myllm.harness.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Harness 控制平面配置，默认关闭。 */
@ConfigurationProperties(prefix = "harness")
public class HarnessProperties {

    private boolean enabled = false;
    private final Worker worker = new Worker();
    private final Defaults defaults = new Defaults();
    private final Policy policy = new Policy();
    private final Tools tools = new Tools();
    private final Model model = new Model();
    private final Context context = new Context();
    private final Verification verification = new Verification();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Worker getWorker() {
        return worker;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public Policy getPolicy() {
        return policy;
    }

    public Tools getTools() {
        return tools;
    }

    public Model getModel() {
        return model;
    }

    public Context getContext() {
        return context;
    }

    public Verification getVerification() {
        return verification;
    }

    /** 送入模型的上下文预算；使用保守 Token 估算，不依赖具体模型 tokenizer。 */
    public static class Context {
        private int maxPromptTokens = 12_000;
        private int maxObservationTokens = 6_000;
        private int maxSingleObservationTokens = 2_000;
        private int maxObservations = 12;

        public int getMaxPromptTokens() {
            return Math.max(1000, maxPromptTokens);
        }

        public void setMaxPromptTokens(int maxPromptTokens) {
            this.maxPromptTokens = maxPromptTokens;
        }

        public int getMaxObservationTokens() {
            return Math.max(200, maxObservationTokens);
        }

        public void setMaxObservationTokens(int maxObservationTokens) {
            this.maxObservationTokens = maxObservationTokens;
        }

        public int getMaxSingleObservationTokens() {
            return Math.max(100, maxSingleObservationTokens);
        }

        public void setMaxSingleObservationTokens(int maxSingleObservationTokens) {
            this.maxSingleObservationTokens = maxSingleObservationTokens;
        }

        public int getMaxObservations() {
            return Math.max(1, maxObservations);
        }

        public void setMaxObservations(int maxObservations) {
            this.maxObservations = maxObservations;
        }
    }

    /** 最终回答机械校验和 Repair 上限。 */
    public static class Verification {
        private int maxRepairAttempts = 2;
        private int maxAnswerChars = 12_000;
        private boolean requireCitationsWhenEvidencePresent = true;

        public int getMaxRepairAttempts() {
            return Math.max(0, Math.min(2, maxRepairAttempts));
        }

        public void setMaxRepairAttempts(int maxRepairAttempts) {
            this.maxRepairAttempts = maxRepairAttempts;
        }

        public int getMaxAnswerChars() {
            return Math.max(100, maxAnswerChars);
        }

        public void setMaxAnswerChars(int maxAnswerChars) {
            this.maxAnswerChars = maxAnswerChars;
        }

        public boolean isRequireCitationsWhenEvidencePresent() {
            return requireCitationsWhenEvidencePresent;
        }

        public void setRequireCitationsWhenEvidencePresent(boolean requireCitationsWhenEvidencePresent) {
            this.requireCitationsWhenEvidencePresent = requireCitationsWhenEvidencePresent;
        }
    }

    public static class Model {
        /** spring-ai 或 fixture（确定性回放）。 */
        private String gateway = "spring-ai";
        private int fixtureInputTokens = 100;
        private int fixtureOutputTokens = 50;

        public String getGateway() {
            return gateway == null ? "spring-ai" : gateway;
        }

        public void setGateway(String gateway) {
            this.gateway = gateway;
        }

        public int getFixtureInputTokens() {
            return Math.max(0, fixtureInputTokens);
        }

        public void setFixtureInputTokens(int fixtureInputTokens) {
            this.fixtureInputTokens = fixtureInputTokens;
        }

        public int getFixtureOutputTokens() {
            return Math.max(0, fixtureOutputTokens);
        }

        public void setFixtureOutputTokens(int fixtureOutputTokens) {
            this.fixtureOutputTokens = fixtureOutputTokens;
        }
    }

    public static class Tools {
        private long defaultTimeoutMs = 30_000;
        private int maxResultBytes = 65_536;
        private List<String> defaultAllowlist = List.of(
                "knowledge.search",
                "file.list",
                "file.task-status",
                "graph.status");

        public long getDefaultTimeoutMs() {
            return Math.max(1000, defaultTimeoutMs);
        }

        public void setDefaultTimeoutMs(long defaultTimeoutMs) {
            this.defaultTimeoutMs = defaultTimeoutMs;
        }

        public int getMaxResultBytes() {
            return Math.max(1024, maxResultBytes);
        }

        public void setMaxResultBytes(int maxResultBytes) {
            this.maxResultBytes = maxResultBytes;
        }

        public List<String> getDefaultAllowlist() {
            return defaultAllowlist == null ? List.of() : List.copyOf(defaultAllowlist);
        }

        public void setDefaultAllowlist(List<String> defaultAllowlist) {
            this.defaultAllowlist = defaultAllowlist;
        }
    }

    public static class Policy {
        private boolean sensitiveReadToolsEnabled = false;
        private boolean writeToolsEnabled = false;
        private boolean destructiveToolsEnabled = false;
        private boolean externalToolsEnabled = false;

        public boolean isSensitiveReadToolsEnabled() {
            return sensitiveReadToolsEnabled;
        }

        public void setSensitiveReadToolsEnabled(boolean sensitiveReadToolsEnabled) {
            this.sensitiveReadToolsEnabled = sensitiveReadToolsEnabled;
        }

        public boolean isWriteToolsEnabled() {
            return writeToolsEnabled;
        }

        public void setWriteToolsEnabled(boolean writeToolsEnabled) {
            this.writeToolsEnabled = writeToolsEnabled;
        }

        public boolean isDestructiveToolsEnabled() {
            return destructiveToolsEnabled;
        }

        public void setDestructiveToolsEnabled(boolean destructiveToolsEnabled) {
            this.destructiveToolsEnabled = destructiveToolsEnabled;
        }

        public boolean isExternalToolsEnabled() {
            return externalToolsEnabled;
        }

        public void setExternalToolsEnabled(boolean externalToolsEnabled) {
            this.externalToolsEnabled = externalToolsEnabled;
        }
    }

    public static class Worker {
        private long pollIntervalMs = 1000;
        private long leaseDurationMs = 30_000;
        private long heartbeatIntervalMs = 10_000;
        private int maxConcurrency = 2;

        public long getPollIntervalMs() {
            return Math.max(200, pollIntervalMs);
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getLeaseDurationMs() {
            return Math.max(5000, leaseDurationMs);
        }

        public void setLeaseDurationMs(long leaseDurationMs) {
            this.leaseDurationMs = leaseDurationMs;
        }

        public long getHeartbeatIntervalMs() {
            return Math.max(2000, heartbeatIntervalMs);
        }

        public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
        }

        public int getMaxConcurrency() {
            return Math.max(1, maxConcurrency);
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }
    }

    public static class Defaults {
        private int maxSteps = 8;
        private int maxModelCalls = 6;
        private int maxToolCalls = 10;
        private long maxWallTimeMs = 120_000;
        private int maxInputTokens = 30_000;
        private int maxOutputTokens = 6_000;

        public int getMaxSteps() {
            return Math.max(1, maxSteps);
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public int getMaxModelCalls() {
            return Math.max(1, maxModelCalls);
        }

        public void setMaxModelCalls(int maxModelCalls) {
            this.maxModelCalls = maxModelCalls;
        }

        public int getMaxToolCalls() {
            return Math.max(1, maxToolCalls);
        }

        public void setMaxToolCalls(int maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
        }

        public long getMaxWallTimeMs() {
            return Math.max(10_000, maxWallTimeMs);
        }

        public void setMaxWallTimeMs(long maxWallTimeMs) {
            this.maxWallTimeMs = maxWallTimeMs;
        }

        public int getMaxInputTokens() {
            return Math.max(0, maxInputTokens);
        }

        public void setMaxInputTokens(int maxInputTokens) {
            this.maxInputTokens = maxInputTokens;
        }

        public int getMaxOutputTokens() {
            return Math.max(0, maxOutputTokens);
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }
    }
}
