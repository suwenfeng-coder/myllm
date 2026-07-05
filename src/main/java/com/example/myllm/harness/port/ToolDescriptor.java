package com.example.myllm.harness.port;

import com.example.myllm.harness.domain.ToolRisk;

/** 工具元数据：风险、超时、结果大小与审批策略。 */
public record ToolDescriptor(
        String name,
        String version,
        String description,
        ToolRisk riskLevel,
        long timeoutMs,
        boolean idempotent,
        boolean approvalRequired,
        int maxResultBytes) {

    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name 不能为空");
        }
        if (version == null || version.isBlank()) {
            version = "1";
        }
        timeoutMs = Math.max(1000, timeoutMs);
        maxResultBytes = Math.max(1024, maxResultBytes);
    }
}
