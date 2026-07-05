package com.example.myllm.support;

import com.example.myllm.dto.InferenceMetrics;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.time.Duration;

public final class InferenceMetricsExtractor {

    private static final String EVAL_DURATION = "eval-duration";
    private static final String PROMPT_EVAL_DURATION = "prompt-eval-duration";
    private static final String LOAD_DURATION = "load-duration";
    private static final String TOTAL_DURATION = "total-duration";

    private InferenceMetricsExtractor() {
    }

    public static InferenceMetrics from(ChatResponse chatResponse, long wallClockDurationMs) {
        ChatResponseMetadata metadata = chatResponse != null ? chatResponse.getMetadata() : null;
        Usage usage = metadata != null ? metadata.getUsage() : null;

        Long inputTokens = toLong(usage != null ? usage.getPromptTokens() : null);
        Long outputTokens = toLong(usage != null ? usage.getCompletionTokens() : null);
        Long totalTokens = toLong(usage != null ? usage.getTotalTokens() : null);

        Long promptEvalDurationMs = durationToMillis(metadata, PROMPT_EVAL_DURATION);
        Long evalDurationMs = durationToMillis(metadata, EVAL_DURATION);
        Long loadDurationMs = durationToMillis(metadata, LOAD_DURATION);
        Long modelTotalDurationMs = durationToMillis(metadata, TOTAL_DURATION);

        Double inferenceSpeedTps = null;
        if (outputTokens != null && evalDurationMs != null && evalDurationMs > 0) {
            inferenceSpeedTps = outputTokens * 1000.0 / evalDurationMs;
        }

        return new InferenceMetrics(
                inputTokens,
                outputTokens,
                totalTokens,
                inferenceSpeedTps,
                promptEvalDurationMs,
                evalDurationMs,
                loadDurationMs,
                modelTotalDurationMs,
                wallClockDurationMs);
    }

    private static Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private static Long durationToMillis(ChatResponseMetadata metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Duration duration) {
            return duration.toMillis();
        }
        return null;
    }
}
