package com.example.myllm.harness.application;

import com.example.myllm.harness.domain.StepStatus;
import com.example.myllm.harness.domain.StepType;
import com.example.myllm.harness.entity.HarnessStep;
import com.example.myllm.harness.repository.HarnessStepRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Harness 步骤生命周期：创建、完成、失败与恢复时标记中断步骤。 */
@Service
public class HarnessStepService {

    private final HarnessStepRepository stepRepository;
    private final HarnessStepTransactionService transactionService;

    public HarnessStepService(
            HarnessStepRepository stepRepository,
            HarnessStepTransactionService transactionService) {
        this.stepRepository = stepRepository;
        this.transactionService = transactionService;
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

    public void succeed(
            String stepId,
            String outputPreview,
            Integer inputTokens,
            Integer outputTokens,
            long durationMs) {
        transactionService.succeed(stepId, outputPreview, null, inputTokens, outputTokens, durationMs);
    }

    public void succeed(
            String stepId,
            String outputPreview,
            String decisionSummaryOverride,
            Integer inputTokens,
            Integer outputTokens,
            long durationMs) {
        transactionService.succeed(
                stepId, outputPreview, decisionSummaryOverride, inputTokens, outputTokens, durationMs);
    }

    public void fail(String stepId, String errorCode, String errorMessage) {
        transactionService.fail(stepId, errorCode, errorMessage);
    }

    /** 将中断的 RUNNING 步骤标记为失败，便于租约恢复后继续执行。 */
    @Transactional
    public int recoverInterruptedSteps(String runId) {
        List<HarnessStep> steps = stepRepository.findByRunIdOrderBySequenceNoAsc(runId);
        int recovered = 0;
        for (HarnessStep step : steps) {
            if (step.getStatus() == StepStatus.RUNNING) {
                transactionService.fail(step.getStepId(), "STEP_INTERRUPTED", "步骤因 Worker 租约恢复而中断");
                recovered++;
            }
        }
        return recovered;
    }

    @Transactional(readOnly = true)
    public List<HarnessStep> listSteps(String runId) {
        return stepRepository.findByRunIdOrderBySequenceNoAsc(runId);
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
