package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.config.HarnessProperties;
import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolIds;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolPolicyEngineTests {

    private ToolPolicyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ToolPolicyEngine(new HarnessProperties());
    }

    @Test
    void allowsReadOnlyToolInDefaultAllowlist() {
        ToolDescriptor descriptor = new ToolDescriptor(
                ToolIds.KNOWLEDGE_SEARCH, "1", "test", ToolRisk.READ_ONLY, 1000, true, false, 1024);
        engine.validate(ToolIds.KNOWLEDGE_SEARCH, descriptor, new ToolExecutionContext(null, null, null, Set.of()));
    }

    @Test
    void blocksPermanentlyDeniedTool() {
        String toolName = "shell.execute";
        ToolDescriptor descriptor = sampleDescriptor(toolName);
        ToolExecutionContext context = contextWithAllowAll();

        HarnessDomainException ex = assertThrows(
                HarnessDomainException.class,
                () -> engine.validate(toolName, descriptor, context));
        assertEquals(HarnessErrorCode.TOOL_NOT_ALLOWED, ex.getErrorCode());
    }

    @Test
    void blocksToolOutsideAllowlist() {
        String toolName = "unknown.tool";
        ToolDescriptor descriptor = sampleDescriptor(toolName);
        ToolExecutionContext context = new ToolExecutionContext(null, null, null, Set.of());

        HarnessDomainException ex = assertThrows(
                HarnessDomainException.class,
                () -> engine.validate(toolName, descriptor, context));
        assertEquals(HarnessErrorCode.TOOL_NOT_ALLOWED, ex.getErrorCode());
    }

    @Test
    void blocksWriteToolByDefault() {
        ToolDescriptor writeTool = new ToolDescriptor(
                "upload.retry", "1", "write", ToolRisk.WRITE, 1000, false, false, 1024);
        ToolExecutionContext context = new ToolExecutionContext(null, null, null, Set.of("upload.retry"));

        HarnessDomainException ex = assertThrows(
                HarnessDomainException.class,
                () -> engine.validate("upload.retry", writeTool, context));
        assertEquals(HarnessErrorCode.TOOL_NOT_ALLOWED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("WRITE"));
    }

    private static ToolExecutionContext contextWithAllowAll() {
        return new ToolExecutionContext(
                null,
                null,
                null,
                Set.of("shell.execute", "unknown.tool", ToolIds.KNOWLEDGE_SEARCH));
    }

    private static ToolDescriptor sampleDescriptor(String name) {
        return new ToolDescriptor(name, "1", "test", ToolRisk.READ_ONLY, 1000, true, false, 1024);
    }
}
