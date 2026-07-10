package com.example.myllm.harness.application;

import com.example.myllm.harness.config.HarnessProperties;
import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.example.myllm.harness.domain.ToolCallStatus;
import com.example.myllm.harness.entity.HarnessToolCall;
import com.example.myllm.harness.port.HarnessTool;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolRegistry;
import com.example.myllm.harness.port.ToolResult;
import com.example.myllm.harness.repository.HarnessToolCallRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具执行器：策略检查、超时包装、结果截断与幂等审计。
 *
 * <p>不绕过本类直接调用业务 Service 写接口（ADR-001）。</p>
 */
@Service
public class ToolExecutor {

    private static final int PREVIEW_MAX = 2000;

    private final ToolRegistry toolRegistry;
    private final ToolPolicyEngine policyEngine;
    private final HarnessToolCallRepository toolCallRepository;
    private final HarnessProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ToolExecutor(
            ToolRegistry toolRegistry,
            ToolPolicyEngine policyEngine,
            HarnessToolCallRepository toolCallRepository,
            HarnessProperties properties,
            ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.policyEngine = policyEngine;
        this.toolCallRepository = toolCallRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 在 Harness 策略约束下执行一个工具调用。
     *
     * <p>执行顺序固定为：查找工具 → 风险策略校验 → 幂等审计命中检查 → 写 RUNNING 审计 → 带超时调用工具 →
     * 机械检查结果大小 → 写终态审计。工具输出只有通过大小与序列化检查后才允许进入后续 Context。</p>
     *
     * <p>当上下文中没有 runId 时，本方法仍可用于测试或临时调用，但不会持久化 tool_call 审计。</p>
     */
    @Transactional
    public ToolResult<Object> execute(ToolExecutionContext context, String toolName, Object input) {
        ToolExecutionContext safeContext = context == null
                ? new ToolExecutionContext(null, null, null, policyEngine.effectiveAllowlist(null))
                : context;

        HarnessTool<?, ?> tool = toolRegistry.find(toolName)
                .orElseThrow(() -> new HarnessDomainException(
                        HarnessErrorCode.TOOL_NOT_ALLOWED, "未注册工具: " + toolName));

        ToolDescriptor descriptor = tool.descriptor();
        policyEngine.validate(toolName, descriptor, safeContext);

        String idempotencyKey = resolveIdempotencyKey(safeContext, toolName, input);
        boolean persistAudit = hasRunId(safeContext);

        if (persistAudit) {
            Optional<HarnessToolCall> existing =
                    toolCallRepository.findByToolNameAndIdempotencyKey(toolName, idempotencyKey);
            if (existing.isPresent() && existing.get().getStatus() == ToolCallStatus.SUCCEEDED) {
                HarnessToolCall prior = existing.get();
                return ToolResult.ok(null, prior.getResultPreview(), safeDuration(prior.getDurationMs()));
            }
        }

        HarnessToolCall audit = null;
        if (persistAudit) {
            audit = createAuditRecord(safeContext, descriptor, idempotencyKey, input);
            audit.setStatus(ToolCallStatus.RUNNING);
            toolCallRepository.saveAndFlush(audit);
        }

        long timeoutMs = Math.min(descriptor.timeoutMs(), properties.getTools().getDefaultTimeoutMs());
        ToolResult<Object> result = invokeWithTimeout(tool, safeContext, input, timeoutMs);
        result = enforceResultSize(result, descriptor);

        if (persistAudit) {
            HarnessToolCall auditRecord = requireAuditRecord(audit);
            auditRecord.setDurationMs(result.durationMs());
            auditRecord.setResultPreview(truncate(result.resultPreview(), PREVIEW_MAX));
            if (result.success()) {
                auditRecord.setStatus(ToolCallStatus.SUCCEEDED);
                auditRecord.setFinishedAt(LocalDateTime.now(ZoneId.systemDefault()));
            } else {
                auditRecord.setStatus(ToolCallStatus.FAILED);
                auditRecord.setErrorCode(result.errorCode());
                auditRecord.setErrorMessage(truncate(result.errorMessage(), PREVIEW_MAX));
                auditRecord.setFinishedAt(LocalDateTime.now(ZoneId.systemDefault()));
            }
            toolCallRepository.save(auditRecord);
        }
        return result;
    }

    /** 工具声明的结果大小必须机械执行，超限结果不进入上下文。 */
    private ToolResult<Object> enforceResultSize(ToolResult<Object> result, ToolDescriptor descriptor) {
        if (!result.success() || result.output() == null) {
            return result;
        }
        int maxBytes = Math.min(descriptor.maxResultBytes(), properties.getTools().getMaxResultBytes());
        try {
            int actualBytes = objectMapper.writeValueAsBytes(result.output()).length;
            if (actualBytes > maxBytes) {
                return ToolResult.failed(
                        "TOOL_RESULT_TOO_LARGE",
                        "工具结果超过限制: " + actualBytes + " > " + maxBytes + " bytes",
                        result.durationMs());
            }
            return result;
        } catch (Exception e) {
            return ToolResult.failed("TOOL_RESULT_SERIALIZATION_FAILED", "工具结果无法安全序列化", result.durationMs());
        }
    }

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    private static boolean hasRunId(ToolExecutionContext context) {
        return context.runId() != null && !context.runId().isBlank();
    }

    /**
     * 在线程池中执行工具并施加硬超时。
     *
     * <p>超时会 cancel Future 并返回失败结果；工具自身仍应尽量响应中断，因为 Java 线程无法安全强杀正在
     * 阻塞的外部 IO。</p>
     */
    private ToolResult<Object> invokeWithTimeout(
            HarnessTool<?, ?> tool,
            ToolExecutionContext context,
            Object input,
            long timeoutMs) {
        Callable<ToolResult<Object>> task = () -> {
            Object normalizedInput = normalizeInput(tool, input);
            return executeTyped(tool, context, normalizedInput);
        };
        Future<ToolResult<Object>> future = executor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.failed("TOOL_TIMEOUT", "工具执行超时", timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return ToolResult.failed("TOOL_EXECUTION_FAILED", cause.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failed("TOOL_INTERRUPTED", "工具执行被中断", 0);
        }
    }

    private Object normalizeInput(HarnessTool<?, ?> tool, Object input) {
        Class<?> inputType = tool.inputType();
        if (Void.class.equals(inputType)) {
            return null;
        }
        if (input == null) {
            throw new HarnessDomainException(HarnessErrorCode.VALIDATION_FAILED, "工具输入不能为空");
        }
        if (!inputType.isInstance(input)) {
            if (input instanceof java.util.Map<?, ?> map) {
                return objectMapper.convertValue(map, inputType);
            }
            throw new HarnessDomainException(
                    HarnessErrorCode.VALIDATION_FAILED,
                    "工具输入类型不匹配，期望 " + inputType.getSimpleName());
        }
        return input;
    }

    private static <I> ToolResult<Object> executeTyped(
            HarnessTool<I, ?> tool,
            ToolExecutionContext context,
            Object input) {
        I typedInput = tool.inputType().cast(input);
        ToolResult<?> result = tool.execute(context, typedInput);
        if (result == null) {
            return ToolResult.failed("TOOL_EXECUTION_FAILED", "工具返回空结果", 0);
        }
        return new ToolResult<>(
                result.success(),
                result.output(),
                result.resultPreview(),
                result.errorCode(),
                result.errorMessage(),
                result.durationMs());
    }

    private static HarnessToolCall requireAuditRecord(HarnessToolCall audit) {
        if (audit == null) {
            throw new IllegalStateException("工具审计记录未初始化");
        }
        return audit;
    }

    private HarnessToolCall createAuditRecord(
            ToolExecutionContext context,
            ToolDescriptor descriptor,
            String idempotencyKey,
            Object input) {
        HarnessToolCall auditRecord = new HarnessToolCall();
        auditRecord.setToolCallId(UUID.randomUUID().toString());
        auditRecord.setRunId(context.runId());
        auditRecord.setStepId(context.stepId());
        auditRecord.setToolName(descriptor.name());
        auditRecord.setToolVersion(descriptor.version());
        auditRecord.setRiskLevel(descriptor.riskLevel());
        auditRecord.setStatus(ToolCallStatus.PENDING);
        auditRecord.setIdempotencyKey(idempotencyKey);
        auditRecord.setArgumentsHash(hashInput(input));
        auditRecord.setArgumentsRedactedJson(redactArguments(input));
        return auditRecord;
    }

    private static String resolveIdempotencyKey(ToolExecutionContext context, String toolName, Object input) {
        if (context.idempotencyKey() != null && !context.idempotencyKey().isBlank()) {
            return context.idempotencyKey().trim();
        }
        return toolName + ":" + hashInput(input);
    }

    private static String redactArguments(Object input) {
        if (input == null) {
            return null;
        }
        String raw = String.valueOf(input);
        return truncate(raw, 500);
    }

    private static String hashInput(Object input) {
        String raw = input == null ? "" : String.valueOf(input);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
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

    private static long safeDuration(Long durationMs) {
        return durationMs == null ? 0L : durationMs;
    }
}
