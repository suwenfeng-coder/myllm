package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.config.HarnessConfiguration;
import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.example.myllm.harness.domain.RunType;
import com.example.myllm.harness.domain.ToolCallStatus;
import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.entity.HarnessRun;
import com.example.myllm.harness.port.HarnessTool;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolRegistry;
import com.example.myllm.harness.port.ToolResult;
import com.example.myllm.harness.repository.HarnessToolCallRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@Import({
        ToolExecutor.class,
        ToolArgumentAuditSummarizer.class,
        ToolPolicyEngine.class,
        HarnessConfiguration.class,
        ToolExecutorTests.TestConfig.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class
})
@TestPropertySource(properties = "harness.enabled=false")
class ToolExecutorTests {

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private HarnessRunService harnessRunService;

    @Autowired
    private HarnessToolCallRepository toolCallRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void executesAllowedToolAndPersistsAudit() {
        HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant", 1, "hash", RunType.AGENT_LOOP, "obj", "req-tool-1", null, null, 8));
        ToolExecutionContext ctx = new ToolExecutionContext(
                run.getRunId(), null, "idem-1", Set.of(ToolExecutorTests.TestConfig.ECHO_TEST));

        ToolResult<?> result = toolExecutor.execute(ctx, ToolExecutorTests.TestConfig.ECHO_TEST, "hello");
        assertTrue(result.success());
        assertEquals("echo:hello", result.resultPreview());
        assertEquals(1, toolCallRepository.findAll().size());
        assertEquals(ToolCallStatus.SUCCEEDED, toolCallRepository.findAll().get(0).getStatus());
    }

    @Test
    void blocksDeniedTool() {
        String deniedTool = "shell.execute";
        ToolExecutionContext ctx = new ToolExecutionContext("run-x", null, "k1", Set.of(deniedTool));

        HarnessDomainException ex = assertThrows(
                HarnessDomainException.class,
                () -> toolExecutor.execute(ctx, deniedTool, null));
        assertEquals(HarnessErrorCode.TOOL_NOT_ALLOWED, ex.getErrorCode());
    }

    @Test
    void idempotentReplayReturnsPriorPreview() {
        HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant", 1, "hash", RunType.AGENT_LOOP, "obj", "req-tool-2", null, null, 8));
        ToolExecutionContext ctx = new ToolExecutionContext(
                run.getRunId(), null, "idem-2", Set.of(ToolExecutorTests.TestConfig.ECHO_TEST));

        toolExecutor.execute(ctx, ToolExecutorTests.TestConfig.ECHO_TEST, "once");
        ToolResult<?> second = toolExecutor.execute(ctx, ToolExecutorTests.TestConfig.ECHO_TEST, "once");
        assertTrue(second.success());
        assertEquals("echo:once", second.resultPreview());
        assertEquals(1, toolCallRepository.count());
    }

    @Test
    void rejectsToolResultLargerThanDeclaredLimit() {
        HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant", 1, "hash", RunType.AGENT_LOOP, "obj", "req-tool-large", null, null, 8));
        ToolExecutionContext ctx = new ToolExecutionContext(
                run.getRunId(), null, "idem-large", Set.of(ToolExecutorTests.TestConfig.ECHO_TEST));

        ToolResult<?> result = toolExecutor.execute(
                ctx, ToolExecutorTests.TestConfig.ECHO_TEST, "x".repeat(5000));

        assertFalse(result.success());
        assertEquals("TOOL_RESULT_TOO_LARGE", result.errorCode());
        assertEquals(ToolCallStatus.FAILED, toolCallRepository.findAll().get(0).getStatus());
    }

    @Test
    void nullToolResultBecomesFailedEnvelope() {
        ToolResult<?> result = toolExecutor.execute(
                new ToolExecutionContext(null, null, null, Set.of(ToolExecutorTests.TestConfig.NULL_TEST)),
                ToolExecutorTests.TestConfig.NULL_TEST,
                "ignored");

        assertFalse(result.success());
        assertEquals("TOOL_EXECUTION_FAILED", result.errorCode());
    }

    @Test
    void persistsValidStructureOnlyArgumentAudit() throws Exception {
        HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant",
                1,
                "hash",
                RunType.AGENT_LOOP,
                "obj",
                "req-tool-audit",
                null,
                null,
                8));
        String secret = "密钥-不可持久化-123";
        ToolExecutionContext context = new ToolExecutionContext(
                run.getRunId(), null, "idem-audit", Set.of(ToolExecutorTests.TestConfig.ECHO_TEST));

        ToolResult<?> result = toolExecutor.execute(context, ToolExecutorTests.TestConfig.ECHO_TEST, secret);

        assertTrue(result.success());
        String auditJson = toolCallRepository.findAll().get(0).getArgumentsRedactedJson();
        JsonNode audit = objectMapper.readTree(auditJson);
        assertEquals(1, audit.path("schemaVersion").asInt());
        assertEquals("String", audit.path("inputType").asText());
        assertEquals("string", audit.at("/summary/type").asText());
        assertEquals(secret.length(), audit.at("/summary/length").asInt());
        assertFalse(auditJson.contains(secret));
    }

    @Test
    void persistsNullArgumentAuditForVoidTool() {
        HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant",
                1,
                "hash",
                RunType.AGENT_LOOP,
                "obj",
                "req-tool-void-audit",
                null,
                null,
                8));
        ToolExecutionContext context = new ToolExecutionContext(
                run.getRunId(), null, "idem-void-audit", Set.of(ToolExecutorTests.TestConfig.VOID_TEST));

        ToolResult<?> result = toolExecutor.execute(context, ToolExecutorTests.TestConfig.VOID_TEST, null);

        assertTrue(result.success());
        assertNull(toolCallRepository.findAll().get(0).getArgumentsRedactedJson());
    }

    @Test
    void auditFallbackDoesNotBlockToolExecution() throws Exception {
        HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant",
                1,
                "hash",
                RunType.AGENT_LOOP,
                "obj",
                "req-tool-fallback-audit",
                null,
                null,
                8));
        ToolExecutionContext context = new ToolExecutionContext(
                run.getRunId(),
                null,
                "idem-fallback-audit",
                Set.of(ToolExecutorTests.TestConfig.AUDIT_FAILURE_TEST));

        ToolResult<?> result = toolExecutor.execute(
                context,
                ToolExecutorTests.TestConfig.AUDIT_FAILURE_TEST,
                new AuditFailureInput());

        assertTrue(result.success());
        String auditJson = toolCallRepository.findAll().get(0).getArgumentsRedactedJson();
        assertEquals(
                objectMapper.readTree("{\"schemaVersion\":1,\"summary\":{\"type\":\"unavailable\"}}"),
                objectMapper.readTree(auditJson));
    }

    static class TestConfig {
        static final String ECHO_TEST = "echo.test";
        static final String NULL_TEST = "null.test";
        static final String VOID_TEST = "void.test";
        static final String AUDIT_FAILURE_TEST = "audit.failure.test";

        @Bean
        @Primary
        ToolRegistry toolRegistry(
                EchoTestTool echoTestTool,
                NullResultTool nullResultTool,
                VoidTestTool voidTestTool,
                AuditFailureTestTool auditFailureTestTool) {
            DefaultToolRegistry registry = new DefaultToolRegistry(java.util.List.of());
            registry.register(echoTestTool);
            registry.register(nullResultTool);
            registry.register(voidTestTool);
            registry.register(auditFailureTestTool);
            return registry;
        }

        @Bean
        EchoTestTool echoTestTool() {
            return new EchoTestTool();
        }

        @Bean
        NullResultTool nullResultTool() {
            return new NullResultTool();
        }

        @Bean
        VoidTestTool voidTestTool() {
            return new VoidTestTool();
        }

        @Bean
        AuditFailureTestTool auditFailureTestTool() {
            return new AuditFailureTestTool();
        }

        @Bean
        HarnessRunService harnessRunService(
                com.example.myllm.harness.repository.HarnessRunRepository runRepository,
                HarnessEventService eventService,
                com.example.myllm.harness.config.HarnessProperties properties) {
            return new HarnessRunService(runRepository, eventService, properties);
        }

        @Bean
        HarnessEventService harnessEventService(
                com.example.myllm.harness.repository.HarnessRunRepository runRepository,
                com.example.myllm.harness.repository.HarnessEventRepository eventRepository,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new HarnessEventService(runRepository, eventRepository, objectMapper);
        }
    }

    static class EchoTestTool implements HarnessTool<String, String> {
        private static final String TOOL_NAME = TestConfig.ECHO_TEST;

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    TOOL_NAME, "1", "echo", ToolRisk.READ_ONLY, 5000, true, false, 4096);
        }

        @Override
        public Class<String> inputType() {
            return String.class;
        }

        @Override
        public ToolResult<String> execute(ToolExecutionContext context, String input) {
            return ToolResult.ok(input, "echo:" + input, 1);
        }
    }

    static class NullResultTool implements HarnessTool<String, String> {
        private static final String TOOL_NAME = TestConfig.NULL_TEST;

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    TOOL_NAME, "1", "null-result", ToolRisk.READ_ONLY, 5000, true, false, 4096);
        }

        @Override
        public Class<String> inputType() {
            return String.class;
        }

        @Override
        public ToolResult<String> execute(ToolExecutionContext context, String input) {
            return null;
        }
    }

    static class VoidTestTool implements HarnessTool<Void, String> {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    TestConfig.VOID_TEST, "1", "void", ToolRisk.READ_ONLY, 5000, true, false, 4096);
        }

        @Override
        public Class<Void> inputType() {
            return Void.class;
        }

        @Override
        public ToolResult<String> execute(ToolExecutionContext context, Void input) {
            return ToolResult.ok("done", "void-ok", 1);
        }
    }

    static class AuditFailureTestTool implements HarnessTool<AuditFailureInput, String> {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    TestConfig.AUDIT_FAILURE_TEST,
                    "1",
                    "audit-failure",
                    ToolRisk.READ_ONLY,
                    5000,
                    true,
                    false,
                    4096);
        }

        @Override
        public Class<AuditFailureInput> inputType() {
            return AuditFailureInput.class;
        }

        @Override
        public ToolResult<String> execute(ToolExecutionContext context, AuditFailureInput input) {
            return ToolResult.ok("done", "audit-fallback-ok", 1);
        }
    }

    static final class AuditFailureInput {

        public String getSecret() {
            throw new IllegalStateException("测试读取失败");
        }
    }
}
