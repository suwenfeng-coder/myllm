package com.example.myllm.harness.api;

import com.example.myllm.harness.application.HarnessDefinitionRegistry;
import com.example.myllm.harness.application.HarnessEventService;
import com.example.myllm.harness.application.HarnessRunService;
import com.example.myllm.harness.application.HarnessStepService;
import com.example.myllm.harness.config.HarnessProperties;
import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.example.myllm.harness.domain.RunType;
import com.example.myllm.harness.entity.HarnessEvent;
import com.example.myllm.harness.entity.HarnessRun;
import com.example.myllm.harness.entity.HarnessStep;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Harness 运维 API：创建运行、查询时间线和发起协作式取消。 */
@RestController
@RequestMapping("/api/harness/runs")
@ConditionalOnProperty(name = "harness.enabled", havingValue = "true")
public class HarnessRunController {

    private static final String DEFAULT_DEFINITION = "knowledge-assistant";
    private static final int DEFINITION_VERSION = 1;

    private final HarnessRunService runService;
    private final HarnessStepService stepService;
    private final HarnessEventService eventService;
    private final HarnessDefinitionRegistry definitionRegistry;
    private final HarnessProperties properties;

    public HarnessRunController(
            HarnessRunService runService,
            HarnessStepService stepService,
            HarnessEventService eventService,
            HarnessDefinitionRegistry definitionRegistry,
            HarnessProperties properties) {
        this.runService = runService;
        this.stepService = stepService;
        this.eventService = eventService;
        this.definitionRegistry = definitionRegistry;
        this.properties = properties;
    }

    /** 创建后立即进入 QUEUED；clientRequestId 用于客户端安全重试。 */
    @PostMapping
    public ResponseEntity<RunResponse> create(@Valid @RequestBody CreateRunRequest request) {
        String definitionId = request.definitionId() == null || request.definitionId().isBlank()
                ? DEFAULT_DEFINITION
                : request.definitionId().trim();
        if (definitionRegistry.allowedTools(definitionId).isEmpty()) {
            throw new HarnessDomainException(
                    HarnessErrorCode.VALIDATION_FAILED, "未知或未启用的 Harness 定义: " + definitionId);
        }
        int configuredMaxSteps = properties.getDefaults().getMaxSteps();
        int maxSteps = request.maxSteps() == null
                ? configuredMaxSteps
                : Math.min(request.maxSteps(), configuredMaxSteps);
        String definitionHash = sha256(definitionId + ":" + DEFINITION_VERSION + ":"
                + String.join(",", definitionRegistry.allowedTools(definitionId).stream().sorted().toList()));
        HarnessRun run = runService.createRun(new HarnessRunService.CreateRunCommand(
                definitionId,
                DEFINITION_VERSION,
                definitionHash,
                RunType.AGENT_LOOP,
                request.objective(),
                request.clientRequestId(),
                request.traceId(),
                null,
                maxSteps));
        return ResponseEntity.accepted()
                .location(URI.create("/api/harness/runs/" + run.getRunId()))
                .body(RunResponse.from(run));
    }

    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable String runId) {
        return RunResponse.from(runService.findRun(runId)
                .orElseThrow(() -> new HarnessDomainException(
                        HarnessErrorCode.RUN_NOT_FOUND, "run 不存在: " + runId)));
    }

    @GetMapping("/{runId}/steps")
    public List<StepResponse> steps(@PathVariable String runId) {
        requireRun(runId);
        return stepService.listSteps(runId).stream().map(StepResponse::from).toList();
    }

    @GetMapping("/{runId}/events")
    public List<EventResponse> events(@PathVariable String runId) {
        return eventService.listEvents(runId).stream().map(EventResponse::from).toList();
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<RunResponse> cancel(@PathVariable String runId) {
        return ResponseEntity.accepted().body(RunResponse.from(runService.requestCancel(runId)));
    }

    private void requireRun(String runId) {
        if (runService.findRun(runId).isEmpty()) {
            throw new HarnessDomainException(HarnessErrorCode.RUN_NOT_FOUND, "run 不存在: " + runId);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public record CreateRunRequest(
            @NotBlank @Size(max = 8000) String objective,
            @Size(max = 64) String definitionId,
            @Size(max = 128) String clientRequestId,
            @Size(max = 64) String traceId,
            @Min(1) @Max(50) Integer maxSteps) {
    }

    public record RunResponse(
            String runId,
            String definitionId,
            String runType,
            String status,
            int currentStep,
            int maxSteps,
            int modelCallCount,
            int toolCallCount,
            int inputTokens,
            int outputTokens,
            boolean cancelRequested,
            String finalOutputPreview,
            String errorCode,
            String errorMessage,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime startedAt,
            java.time.LocalDateTime finishedAt,
            java.time.LocalDateTime updatedAt) {

        static RunResponse from(HarnessRun run) {
            return new RunResponse(
                    run.getRunId(), run.getDefinitionId(), run.getRunType().name(), run.getStatus().name(),
                    run.getCurrentStep(), run.getMaxSteps(), run.getModelCallCount(), run.getToolCallCount(),
                    run.getInputTokens(), run.getOutputTokens(), run.isCancelRequested(),
                    run.getFinalOutputPreview(), run.getErrorCode(), run.getErrorMessage(),
                    run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt(), run.getUpdatedAt());
        }
    }

    public record StepResponse(
            String stepId, int sequenceNo, String stepType, String status, int attempt,
            String decisionSummary, Integer inputTokens, Integer outputTokens, Long durationMs,
            String errorCode, String errorMessage,
            java.time.LocalDateTime startedAt, java.time.LocalDateTime finishedAt) {

        static StepResponse from(HarnessStep step) {
            return new StepResponse(
                    step.getStepId(), step.getSequenceNo(), step.getStepType().name(), step.getStatus().name(),
                    step.getAttempt(), step.getDecisionSummary(), step.getInputTokens(), step.getOutputTokens(),
                    step.getDurationMs(), step.getErrorCode(), step.getErrorMessage(),
                    step.getStartedAt(), step.getFinishedAt());
        }
    }

    public record EventResponse(
            String eventId, int sequenceNo, String eventType, String payloadRedactedJson,
            java.time.LocalDateTime createdAt) {

        static EventResponse from(HarnessEvent event) {
            return new EventResponse(
                    event.getEventId(), event.getSequenceNo(), event.getEventType(),
                    event.getPayloadRedactedJson(), event.getCreatedAt());
        }
    }
}
