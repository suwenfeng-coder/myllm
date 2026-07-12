package com.example.myllm.harness.application;

import com.example.myllm.harness.config.HarnessProperties;
import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 工具 allowlist 与风险策略检查。 */
@Component
public class ToolPolicyEngine {

    private final HarnessProperties properties;

    public ToolPolicyEngine(HarnessProperties properties) {
        this.properties = properties;
    }

    public void validate(String toolName, ToolDescriptor descriptor, ToolExecutionContext context) {
        if (PermanentlyDeniedTools.isDenied(toolName)) {
            throw new HarnessDomainException(
                    HarnessErrorCode.TOOL_NOT_ALLOWED,
                    "工具被永久禁止: " + toolName);
        }
        if (!isAllowedByList(toolName, context)) {
            throw new HarnessDomainException(
                    HarnessErrorCode.TOOL_NOT_ALLOWED,
                    "工具不在 allowlist: " + toolName);
        }
        validateRisk(descriptor.riskLevel());
        if (descriptor.approvalRequired()) {
            throw new HarnessDomainException(
                    HarnessErrorCode.APPROVAL_REQUIRED,
                    "工具需要人工审批: " + toolName);
        }
    }

    public Set<String> effectiveAllowlist(ToolExecutionContext context) {
        if (context != null && !context.allowedToolNames().isEmpty()) {
            return context.allowedToolNames();
        }
        List<String> configured = properties.getTools().getDefaultAllowlist();
        return new LinkedHashSet<>(configured);
    }

    private boolean isAllowedByList(String toolName, ToolExecutionContext context) {
        return effectiveAllowlist(context).contains(toolName);
    }

    private void validateRisk(ToolRisk risk) {
        HarnessProperties.Policy policy = properties.getPolicy();
        if (risk == ToolRisk.READ_ONLY) {
            return;
        }
        if (risk == ToolRisk.SENSITIVE_READ && !policy.isSensitiveReadToolsEnabled()) {
            throw new HarnessDomainException(HarnessErrorCode.TOOL_NOT_ALLOWED, "SENSITIVE_READ 工具未启用");
        }
        if (risk == ToolRisk.WRITE && !policy.isWriteToolsEnabled()) {
            throw new HarnessDomainException(HarnessErrorCode.TOOL_NOT_ALLOWED, "WRITE 工具未启用");
        }
        if (risk == ToolRisk.DESTRUCTIVE && !policy.isDestructiveToolsEnabled()) {
            throw new HarnessDomainException(HarnessErrorCode.TOOL_NOT_ALLOWED, "DESTRUCTIVE 工具未启用");
        }
        if (risk == ToolRisk.EXTERNAL && !policy.isExternalToolsEnabled()) {
            throw new HarnessDomainException(HarnessErrorCode.TOOL_NOT_ALLOWED, "EXTERNAL 工具未启用");
        }
    }
}
