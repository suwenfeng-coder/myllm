package com.example.myllm.harness.application;

import com.example.myllm.harness.domain.StepStateMachine;
import com.example.myllm.harness.domain.StepStatus;
import com.example.myllm.harness.entity.HarnessStep;
import com.example.myllm.harness.repository.HarnessStepRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional Harness step mutations. Invoked through a Spring proxy. */
@Service
public class HarnessStepTransactionService {

    private final HarnessStepRepository stepRepository;

    public HarnessStepTransactionService(HarnessStepRepository stepRepository) {
        this.stepRepository = stepRepository;
    }

    @Transactional
    public void succeed(
            String stepId,
            String outputPreview,
            String decisionSummaryOverride,
            Integer inputTokens,
            Integer outputTokens,
            long durationMs) {
        HarnessStep step = requireStep(stepId);
        StepStateMachine.validateTransition(step.getStatus(), StepStatus.SUCCEEDED);
        step.setStatus(StepStatus.SUCCEEDED);
        step.setOutputHash(hash(outputPreview));
        if (decisionSummaryOverride != null && !decisionSummaryOverride.isBlank()) {
            step.setDecisionSummary(truncate(decisionSummaryOverride, 2000));
        }
        step.setInputTokens(inputTokens);
        step.setOutputTokens(outputTokens);
        step.setDurationMs(durationMs);
        step.setFinishedAt(LocalDateTime.now(ZoneId.systemDefault()));
        stepRepository.save(step);
    }

    @Transactional
    public void fail(String stepId, String errorCode, String errorMessage) {
        HarnessStep step = requireStep(stepId);
        if (step.getStatus() == StepStatus.SUCCEEDED || step.getStatus() == StepStatus.FAILED) {
            return;
        }
        StepStateMachine.validateTransition(step.getStatus(), StepStatus.FAILED);
        step.setStatus(StepStatus.FAILED);
        step.setErrorCode(truncate(errorCode, 64));
        step.setErrorMessage(truncate(errorMessage, 4000));
        step.setFinishedAt(LocalDateTime.now(ZoneId.systemDefault()));
        stepRepository.save(step);
    }

    private HarnessStep requireStep(String stepId) {
        return stepRepository.findById(stepId).orElseThrow(() -> new IllegalArgumentException("step 不存在: " + stepId));
    }

    private static String hash(String value) {
        String raw = value == null ? "" : value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
