package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.config.HarnessConfiguration;
import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.example.myllm.harness.domain.RunStatus;
import com.example.myllm.harness.domain.RunType;
import com.example.myllm.harness.domain.ToolCallStatus;
import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.entity.HarnessRun;
import com.example.myllm.harness.port.HarnessTool;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolIds;
import com.example.myllm.harness.port.ToolRegistry;
import com.example.myllm.harness.port.ToolResult;
import com.example.myllm.harness.repository.HarnessToolCallRepository;
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
        HarnessDomainException ex = assertThrows(
                HarnessDomainException.class,
                () -> toolExecutor.execute(
                        new ToolExecutionContext("run-x", null, "k1", Set.of("shell.execute")),
                        "shell.execute",
                        null));
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

        assertEquals(false, result.success());
        assertEquals("TOOL_RESULT_TOO_LARGE", result.errorCode());
        assertEquals(ToolCallStatus.FAILED, toolCallRepository.findAll().get(0).getStatus());
    }

    static class TestConfig {
        static final String ECHO_TEST = "echo.test";

        @Bean
        @Primary
        ToolRegistry toolRegistry(EchoTestTool echoTestTool) {
            DefaultToolRegistry registry = new DefaultToolRegistry(java.util.List.of());
            registry.register(echoTestTool);
            return registry;
        }

        @Bean
        EchoTestTool echoTestTool() {
            return new EchoTestTool();
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
}
