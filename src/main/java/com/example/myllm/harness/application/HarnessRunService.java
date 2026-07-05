package com.example.myllm.harness.application;

import com.example.myllm.harness.domain.HarnessErrorCode;
import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.RunStateMachine;
import com.example.myllm.harness.domain.RunStatus;
import com.example.myllm.harness.domain.RunType;
import com.example.myllm.harness.entity.HarnessEvent;
import com.example.myllm.harness.entity.HarnessRun;
import com.example.myllm.harness.repository.HarnessRunRepository;
import com.example.myllm.harness.config.HarnessProperties;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Harness 运行生命周期服务：创建、领取、心跳、完成与事件追加。
 *
 * <p>lease_token 作为 fencing token；旧 Worker 持有过期 token 时无法回写状态。</p>
 */
@Service
public class HarnessRunService {

    private static final int PREVIEW_MAX = 2000;
    private static final int ERROR_MAX = 4000;

    private final HarnessRunRepository runRepository;
    private final HarnessEventService eventService;
    private final HarnessProperties properties;

    public HarnessRunService(
            HarnessRunRepository runRepository,
            HarnessEventService eventService,
            HarnessProperties properties) {
        this.runRepository = runRepository;
        this.eventService = eventService;
        this.properties = properties;
    }

    /** 创建运行；{@code clientRequestId} 相同时返回已有 run（幂等）。 */
    @Transactional
    public HarnessRun createRun(CreateRunCommand command) {
        String clientRequestId = trimToNull(command.clientRequestId());
        if (clientRequestId != null) {
            return runRepository.findByClientRequestId(clientRequestId)
                    .orElseGet(() -> persistNewRun(command, clientRequestId));
        }
        return persistNewRun(command, null);
    }

    private HarnessRun persistNewRun(CreateRunCommand command, String clientRequestId) {
        HarnessRun run = new HarnessRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setDefinitionId(requireText(command.definitionId(), "definitionId"));
        run.setDefinitionVersion(Math.max(1, command.definitionVersion()));
        run.setDefinitionHash(requireText(command.definitionHash(), "definitionHash"));
        run.setRunType(command.runType() == null ? RunType.AGENT_LOOP : command.runType());
        run.setStatus(RunStatus.CREATED);
        run.setObjective(trimToNull(command.objective()));
        run.setClientRequestId(clientRequestId);
        run.setTraceId(trimToNull(command.traceId()));
        run.setTransactionLogId(command.transactionLogId());
        run.setMaxSteps(command.maxSteps() != null ? command.maxSteps() : properties.getDefaults().getMaxSteps());
        run.setCurrentStep(0);
        run.setModelCallCount(0);
        run.setToolCallCount(0);
        run.setInputTokens(0);
        run.setOutputTokens(0);
        run.setCancelRequested(false);

        RunStateMachine.validateTransition(run.getStatus(), RunStatus.QUEUED);
        run.setStatus(RunStatus.QUEUED);
        HarnessRun saved = runRepository.saveAndFlush(run);
        eventService.appendEvent(
                saved.getRunId(),
                HarnessEventService.RUN_CREATED,
                eventService.payloadJson(Map.of("definitionId", saved.getDefinitionId())));
        return saved;
    }

    /** 恢复过期 lease 并领取下一个 QUEUED 运行。 */
    @Transactional
    public Optional<ClaimedRun> claimNext(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空");
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        runRepository.recoverExpiredLeases(now);

        return runRepository.lockNextQueued().map(run -> {
            String leaseToken = UUID.randomUUID().toString();
            LocalDateTime expiresAt = now.plusNanos(properties.getWorker().getLeaseDurationMs() * 1_000_000L);

            RunStateMachine.validateTransition(run.getStatus(), RunStatus.RUNNING);
            run.setStatus(RunStatus.RUNNING);
            run.setLeaseOwner(workerId);
            run.setLeaseToken(leaseToken);
            run.setLeaseExpiresAt(expiresAt);
            if (run.getStartedAt() == null) {
                run.setStartedAt(now);
            }
            HarnessRun saved = runRepository.saveAndFlush(run);
            eventService.appendEvent(
                    saved.getRunId(),
                    HarnessEventService.RUN_CLAIMED,
                    eventService.payloadJson(Map.of("workerId", workerId)));
            return new ClaimedRun(saved, leaseToken);
        });
    }

    /** 续租；token 不匹配时不更新任何行。 */
    @Transactional
    public void heartbeat(String runId, String leaseToken) {
        LocalDateTime expiresAt = LocalDateTime.now(ZoneId.systemDefault())
                .plusNanos(properties.getWorker().getLeaseDurationMs() * 1_000_000L);
        int updated = runRepository.heartbeat(runId, leaseToken, expiresAt);
        if (updated == 0) {
            throw new HarnessDomainException(HarnessErrorCode.LEASE_MISMATCH, "lease 不匹配或运行不在 RUNNING 状态");
        }
    }

    @Transactional
    public HarnessRun requestCancel(String runId) {
        HarnessRun run = requireRun(runId);
        if (RunStateMachine.isTerminal(run.getStatus()) || run.getStatus() == RunStatus.FAILED) {
            return run;
        }

        int updated;
        if (run.getStatus() == RunStatus.RUNNING) {
            updated = runRepository.requestCancel(runId);
            if (updated > 0) {
                eventService.appendEvent(runId, HarnessEventService.RUN_CANCEL_REQUESTED, null);
            }
        } else {
            RunStateMachine.validateTransition(run.getStatus(), RunStatus.CANCELLED);
            updated = runRepository.cancelBeforeExecution(runId, "运行在执行前被取消");
            if (updated > 0) {
                eventService.appendEvent(runId, HarnessEventService.RUN_CANCELLED, null);
            }
        }
        return requireRun(runId);
    }

    /** Worker 响应协作式取消；只有持有当前 lease 的 Worker 才能落最终状态。 */
    @Transactional
    public HarnessRun cancelRun(String runId, String leaseToken, String message) {
        RunStateMachine.validateTransition(RunStatus.RUNNING, RunStatus.CANCELLED);
        int updated = runRepository.cancelWithLease(runId, leaseToken, truncate(message, ERROR_MAX));
        if (updated == 0) {
            throw new HarnessDomainException(HarnessErrorCode.LEASE_MISMATCH, "无法取消运行：lease 无效");
        }
        eventService.appendEvent(runId, HarnessEventService.RUN_CANCELLED, null);
        return requireRun(runId);
    }

    @Transactional
    public HarnessRun completeRun(String runId, String leaseToken, String outputPreview, String artifactId) {
        RunStateMachine.validateTransition(RunStatus.RUNNING, RunStatus.SUCCEEDED);
        int updated = runRepository.completeWithLease(
                runId,
                leaseToken,
                RunStatus.SUCCEEDED.name(),
                truncate(outputPreview, PREVIEW_MAX),
                trimToNull(artifactId));
        if (updated == 0) {
            throw new HarnessDomainException(HarnessErrorCode.LEASE_MISMATCH, "无法完成运行：lease 无效");
        }
        eventService.appendEvent(runId, HarnessEventService.RUN_SUCCEEDED, null);
        return requireRun(runId);
    }

    @Transactional
    public HarnessRun failRun(String runId, String leaseToken, HarnessErrorCode errorCode, String message) {
        RunStateMachine.validateTransition(RunStatus.RUNNING, RunStatus.FAILED);
        int updated = runRepository.failWithLease(
                runId,
                leaseToken,
                errorCode.name(),
                truncate(message, ERROR_MAX));
        if (updated == 0) {
            throw new HarnessDomainException(HarnessErrorCode.LEASE_MISMATCH, "无法标记失败：lease 无效");
        }
        eventService.appendEvent(
                runId,
                HarnessEventService.RUN_FAILED,
                eventService.payloadJson(Map.of("errorCode", errorCode.name())));
        return requireRun(runId);
    }

    @Transactional(readOnly = true)
    public Optional<HarnessRun> findRun(String runId) {
        return runRepository.findById(runId);
    }

    public HarnessEvent appendEvent(String runId, String eventType, String payloadRedactedJson) {
        return eventService.appendEvent(runId, eventType, payloadRedactedJson);
    }

    @Transactional
    public int recoverExpiredLeases() {
        return runRepository.recoverExpiredLeases(LocalDateTime.now(ZoneId.systemDefault()));
    }

    private HarnessRun requireRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new HarnessDomainException(HarnessErrorCode.RUN_NOT_FOUND, "run 不存在: " + runId));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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

    public record CreateRunCommand(
            String definitionId,
            int definitionVersion,
            String definitionHash,
            RunType runType,
            String objective,
            String clientRequestId,
            String traceId,
            Long transactionLogId,
            Integer maxSteps) {
    }

    public record ClaimedRun(HarnessRun run, String leaseToken) {
    }
}
