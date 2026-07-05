package com.example.myllm.harness.port;

import java.util.Set;

/** 单次工具调用上下文：运行标识、allowlist 与幂等键。 */
public record ToolExecutionContext(
        String runId,
        String stepId,
        String idempotencyKey,
        Set<String> allowedToolNames) {

    public ToolExecutionContext {
        allowedToolNames = allowedToolNames == null ? Set.of() : Set.copyOf(allowedToolNames);
    }
}
