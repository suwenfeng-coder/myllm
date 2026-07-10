package com.example.myllm.eval.service;

import com.example.myllm.eval.config.EvalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 后台逐题执行模型评测，每次调度处理一题。 */
@Component
@ConditionalOnProperty(prefix = "eval", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ModelEvalTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(ModelEvalTaskWorker.class);

    private final ModelEvalRunService runService;
    private final EvalProperties properties;

    public ModelEvalTaskWorker(ModelEvalRunService runService, EvalProperties properties) {
        this.runService = runService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${eval.task.poll-interval-ms:2000}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        int recovered = runService.recoverTimedOutRuns();
        if (recovered > 0) {
            log.warn("已终止超时评测 run count={}", recovered);
        }

        runService.claimNextRunnableRun().ifPresent(run -> {
            String runId = run.getRunId();
            if (!runService.hasPendingItems(runId)) {
                runService.finalizeRun(runId);
                return;
            }
            log.debug("评测处理单题 runId={} progress={}/{}", runId, run.getCompletedQuestions(), run.getTotalQuestions());
            ModelEvalRunService.ProcessRunResult result = runService.processRun(runId);
            if (result.fatal()) {
                runService.failRun(runId, result.errorMessage());
                log.warn("评测因答题模型不可用而终止 runId={} error={}", runId, result.errorMessage());
                return;
            }
            if (!runService.hasPendingItems(runId)) {
                runService.finalizeRun(runId);
                log.info("评测完成 runId={} score={}", runId, runService.getRun(runId).totalScore());
            }
        });
    }
}
