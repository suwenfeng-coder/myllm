package com.example.myllm.harness.application;

import com.example.myllm.harness.domain.StepStateMachine;
import com.example.myllm.harness.domain.StepStatus;
import com.example.myllm.harness.domain.StepType;
import com.example.myllm.harness.entity.HarnessStep;
import com.example.myllm.harness.repository.HarnessStepRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Harness 步骤生命周期：创建、完成、失败与恢复时标记中断步骤。 */
@Service
public class HarnessStepService {

    private final HarnessStepRepository stepRepository;

    public HarnessStepService(HarnessStepRepository stepRepository) {
        this.stepRepository = stepRepository;
    }

    @Transactional
    public HarnessStep begin(String runId, StepType stepType, String decisionSummary) {
        HarnessStep step = new HarnessStep();
        step.setStepId(UUID.randomUUID().toString());
        step.setRunId(runId);
        step.setSequenceNo(stepRepository.findMaxSequenceNo(runId) + 1);
        step.setStepType(stepType);
        step.setStatus(StepStatus.RUNNING);
        step.setAttempt(1);
        step.setDecisionSummary(truncate(decisionSummary, 2000));
        step.setStartedAt(LocalDateTime.now(ZoneId.systemDefault()));
        return stepRepository.saveAndFlush(step);
    }

    @Transactional
    public void succeed(
            String stepId,
            String outputPreview,
            Integer inputTokens,
            Integer outputTokens,
            long durationMs) {
        succeed(stepId, outputPreview, null, inputTokens, outputTokens, durationMs);
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

    /** 将中断的 RUNNING 步骤标记为失败，便于租约恢复后继续执行。 */
    @Transactional
    public int recoverInterruptedSteps(String runId) {
        List<HarnessStep> steps = stepRepository.findByRunIdOrderBySequenceNoAsc(runId);
        int recovered = 0;
        for (HarnessStep step : steps) {
            if (step.getStatus() == StepStatus.RUNNING) {
                fail(step.getStepId(), "STEP_INTERRUPTED", "步骤因 Worker 租约恢复而中断");
                recovered++;
            }
        }
        return recovered;
    }

    @Transactional(readOnly = true)
    public List<HarnessStep> listSteps(String runId) {
        return stepRepository.findByRunIdOrderBySequenceNoAsc(runId);
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
