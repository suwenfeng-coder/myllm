package com.example.myllm.service;

import com.example.myllm.dto.InferenceMetrics;
import com.example.myllm.entity.TransactionLog;
import com.example.myllm.repository.TransactionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionLogService {

    private static final Logger log = LoggerFactory.getLogger(TransactionLogService.class);

    private final TransactionLogRepository repository;

    public TransactionLogService(TransactionLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionLog saveSuccess(
            String provider,
            String model,
            String inputContent,
            String outputContent,
            String systemPrompt,
            String requestType,
            InferenceMetrics metrics) {
        TransactionLog logEntry = baseRecord(provider, model, inputContent, systemPrompt, requestType);
        logEntry.setOutputContent(outputContent);
        logEntry.setStatus("SUCCESS");
        applyMetrics(logEntry, metrics);

        TransactionLog saved = repository.save(logEntry);
        if (log.isInfoEnabled()) {
            log.info("交易日志已保存 id={} model={} inputTokens={} outputTokens={} totalTokens={} inferenceSpeedTps={} evalDurationMs={}",
                    saved.getId(), model, saved.getInputTokens(), saved.getOutputTokens(),
                    saved.getTotalTokens(), formatSpeed(saved.getInferenceSpeedTps()), saved.getEvalDurationMs());
        }
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionLog saveFailure(
            String provider,
            String model,
            String inputContent,
            String systemPrompt,
            String requestType,
            String errorMessage,
            InferenceMetrics metrics) {
        TransactionLog logEntry = baseRecord(provider, model, inputContent, systemPrompt, requestType);
        logEntry.setStatus("FAILED");
        logEntry.setErrorMessage(errorMessage);
        applyMetrics(logEntry, metrics);

        TransactionLog saved = repository.save(logEntry);
        log.info("交易失败日志已保存 id={} model={} durationMs={}", saved.getId(), model, saved.getDurationMs());
        return saved;
    }

    private static TransactionLog baseRecord(
            String provider,
            String model,
            String inputContent,
            String systemPrompt,
            String requestType) {
        TransactionLog logEntry = new TransactionLog();
        logEntry.setProvider(provider);
        logEntry.setModel(model);
        logEntry.setInputContent(inputContent);
        logEntry.setSystemPrompt(systemPrompt);
        logEntry.setRequestType(requestType);
        return logEntry;
    }

    private static void applyMetrics(TransactionLog logEntry, InferenceMetrics metrics) {
        if (metrics == null) {
            return;
        }
        logEntry.setDurationMs(metrics.wallClockDurationMs());
        logEntry.setInputTokens(metrics.inputTokens());
        logEntry.setOutputTokens(metrics.outputTokens());
        logEntry.setTotalTokens(metrics.totalTokens());
        logEntry.setInferenceSpeedTps(metrics.inferenceSpeedTps());
        logEntry.setPromptEvalDurationMs(metrics.promptEvalDurationMs());
        logEntry.setEvalDurationMs(metrics.evalDurationMs());
        logEntry.setLoadDurationMs(metrics.loadDurationMs());
        logEntry.setModelTotalDurationMs(metrics.modelTotalDurationMs());
    }

    private static String formatSpeed(Double speed) {
        return speed == null ? "n/a" : String.format("%.2f", speed);
    }
}
