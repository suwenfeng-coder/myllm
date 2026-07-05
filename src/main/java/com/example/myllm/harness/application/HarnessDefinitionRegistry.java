package com.example.myllm.harness.application;

import com.example.myllm.harness.domain.ToolRisk;
import java.util.Map;
import java.util.Set;

/** Harness 定义与工具 allowlist（v1 硬编码，后续可改为 YAML）。 */
public class HarnessDefinitionRegistry {

    private static final Map<String, Set<String>> ALLOWLIST = Map.of(
            "knowledge-assistant", Set.of(
                    "knowledge.search",
                    "file.list",
                    "file.task-status",
                    "graph.status"));

    public boolean isToolAllowed(String definitionId, String toolName) {
        if (definitionId == null || toolName == null) {
            return false;
        }
        return ALLOWLIST.getOrDefault(definitionId, Set.of()).contains(toolName);
    }

    public Set<String> allowedTools(String definitionId) {
        return ALLOWLIST.getOrDefault(definitionId, Set.of());
    }

    /** 根据风险等级与配置判断工具是否可自动执行。 */
    public boolean isRiskPermitted(ToolRisk risk, HarnessPolicyView policy) {
        return switch (risk) {
            case READ_ONLY -> true;
            case SENSITIVE_READ -> policy.sensitiveReadToolsEnabled();
            case WRITE -> policy.writeToolsEnabled();
            case DESTRUCTIVE -> policy.destructiveToolsEnabled();
            case EXTERNAL -> policy.externalToolsEnabled();
        };
    }

    public record HarnessPolicyView(
            boolean sensitiveReadToolsEnabled,
            boolean writeToolsEnabled,
            boolean destructiveToolsEnabled,
            boolean externalToolsEnabled) {
    }
}
