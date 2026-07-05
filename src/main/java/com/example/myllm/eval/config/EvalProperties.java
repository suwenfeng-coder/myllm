package com.example.myllm.eval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "eval")
public class EvalProperties {

    private boolean enabled = true;
    private Judge judge = new Judge();
    private Task task = new Task();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Judge getJudge() {
        return judge;
    }

    public void setJudge(Judge judge) {
        this.judge = judge;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public static class Judge {
        private int answerMaxTokens = 800;
        private int judgeMaxTokens = 200;
        private double judgeTemperature = 0.1;

        /** 答题专用 system prompt */
        private String answerSystemPrompt =
                "你是技术面试考生，请认真、完整作答。若不确定请明确说明，不要编造。";

        /** 裁判专用 system prompt */
        private String judgeSystemPrompt =
                "你是严格的技术评测裁判。只输出 JSON，不要输出其他文字。";

        public String getAnswerSystemPrompt() {
            return answerSystemPrompt;
        }

        public void setAnswerSystemPrompt(String answerSystemPrompt) {
            this.answerSystemPrompt = answerSystemPrompt;
        }

        public String getJudgeSystemPrompt() {
            return judgeSystemPrompt;
        }

        public void setJudgeSystemPrompt(String judgeSystemPrompt) {
            this.judgeSystemPrompt = judgeSystemPrompt;
        }

        public int getAnswerMaxTokens() {
            return answerMaxTokens;
        }

        public void setAnswerMaxTokens(int answerMaxTokens) {
            this.answerMaxTokens = answerMaxTokens;
        }

        public int getJudgeMaxTokens() {
            return judgeMaxTokens;
        }

        public void setJudgeMaxTokens(int judgeMaxTokens) {
            this.judgeMaxTokens = judgeMaxTokens;
        }

        public double getJudgeTemperature() {
            return judgeTemperature;
        }

        public void setJudgeTemperature(double judgeTemperature) {
            this.judgeTemperature = judgeTemperature;
        }
    }

    public static class Task {
        private long pollIntervalMs = 2000;
        private long runningTimeoutMs = 7200000;
        private long itemTimeoutMs = 180000;

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getRunningTimeoutMs() {
            return runningTimeoutMs;
        }

        public void setRunningTimeoutMs(long runningTimeoutMs) {
            this.runningTimeoutMs = runningTimeoutMs;
        }

        public long getItemTimeoutMs() {
            return itemTimeoutMs;
        }

        public void setItemTimeoutMs(long itemTimeoutMs) {
            this.itemTimeoutMs = itemTimeoutMs;
        }
    }
}
