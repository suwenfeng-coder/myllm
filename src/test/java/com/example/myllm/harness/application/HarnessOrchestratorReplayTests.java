package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.adapter.model.FixtureReplayModelGateway;
import com.example.myllm.harness.config.HarnessConfiguration;
import com.example.myllm.harness.domain.HarnessActionParser;
import com.example.myllm.harness.domain.RunStatus;
import com.example.myllm.harness.domain.RunType;
import com.example.myllm.harness.domain.StepType;
import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.entity.HarnessRun;
import com.example.myllm.harness.port.HarnessTool;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolRegistry;
import com.example.myllm.harness.port.ToolResult;
import com.example.myllm.harness.repository.HarnessEventRepository;
import com.example.myllm.harness.repository.HarnessRunRepository;
import com.example.myllm.harness.repository.HarnessStepRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({
        HarnessRunService.class,
        HarnessEventService.class,
        HarnessStepTransactionService.class,
        HarnessStepService.class,
        HarnessOrchestrator.class,
        ContextAssembler.class,
        FinalAnswerValidator.class,
        ToolExecutor.class,
        ToolArgumentHasher.class,
        ToolArgumentAuditSummarizer.class,
        ToolPolicyEngine.class,
        HarnessConfiguration.class,
        HarnessOrchestratorReplayTests.ReplayTestConfig.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class
})
@TestPropertySource(properties = {
        "harness.enabled=true",
        "harness.defaults.max-steps=8",
        "harness.defaults.max-model-calls=6"
})
class HarnessOrchestratorReplayTests {

    private static final String CALL_TOOL_JSON = """
            {"action":"CALL_TOOL","tool":"echo.test","arguments":{"msg":"hi"},"summary":"echo"}
            """;
    private static final String FINAL_JSON = """
            {"action":"FINAL","answer":"done with hi","citations":[],"summary":"完成"}
            """;
    private static final String CALL_EVIDENCE_JSON = """
            {"action":"CALL_TOOL","tool":"evidence.test","arguments":{},"summary":"检索证据"}
            """;
    private static final String INVALID_CITATION_JSON = """
            {"action":"FINAL","answer":"错误引用","citations":[{"sourceId":"fake:99"}],"summary":"完成"}
            """;
    private static final String VALID_CITATION_JSON = """
            {"action":"FINAL","answer":"已修复引用","citations":[{"sourceId":"file-1:7"}],"summary":"完成"}
            """;

    @Autowired
    private HarnessOrchestrator orchestrator;

    @Autowired
    private HarnessRunService runService;

    @Autowired
    private HarnessRunRepository runRepository;

    @Autowired
    private HarnessStepRepository stepRepository;

    @Autowired
    private HarnessEventRepository eventRepository;

    @Autowired
    private FixtureReplayModelGateway fixtureGateway;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void fixtureReplayCompletesDeterministically() {
        fixtureGateway.reset(List.of(CALL_TOOL_JSON, FINAL_JSON));
        HarnessRun run = runService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant",
                1,
                "hash-replay",
                RunType.AGENT_LOOP,
                "测试回放",
                "client-replay-001",
                null,
                null,
                8));

        HarnessRunService.ClaimedRun claimed = runService.claimNext("worker-replay").orElseThrow();
        orchestrator.execute(claimed, () -> {});

        HarnessRun finished = runRepository.findById(run.getRunId()).orElseThrow();
        assertEquals(RunStatus.SUCCEEDED, finished.getStatus(), finished.getErrorCode() + ": " + finished.getErrorMessage());
        assertEquals("done with hi", finished.getFinalOutputPreview());
        assertEquals(2, finished.getModelCallCount());
        assertEquals(1, finished.getToolCallCount());
        assertEquals(20, finished.getInputTokens());
        assertEquals(100, finished.getOutputTokens());
        assertEquals(2, fixtureGateway.cursor());

        long modelSteps = stepRepository.findByRunIdOrderBySequenceNoAsc(run.getRunId()).stream()
                .filter(s -> s.getStepType() == StepType.MODEL_DECISION)
                .count();
        long toolSteps = stepRepository.findByRunIdOrderBySequenceNoAsc(run.getRunId()).stream()
                .filter(s -> s.getStepType() == StepType.TOOL_EXECUTION)
                .count();
        assertEquals(2, modelSteps);
        assertEquals(1, toolSteps);
        assertTrue(eventRepository.findByRunIdOrderBySequenceNoAsc(run.getRunId()).size() >= 5);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void budgetExhaustionStopsLoop() {
        fixtureGateway.reset(List.of(CALL_TOOL_JSON, FINAL_JSON));
        HarnessRun run = runService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant",
                1,
                "hash-budget",
                RunType.AGENT_LOOP,
                "预算测试",
                "client-replay-budget",
                null,
                null,
                1));

        HarnessRunService.ClaimedRun claimed = runService.claimNext("worker-budget").orElseThrow();
        orchestrator.execute(claimed, () -> {});

        HarnessRun finished = runRepository.findById(run.getRunId()).orElseThrow();
        assertEquals(RunStatus.FAILED, finished.getStatus());
        assertEquals("BUDGET_EXHAUSTED", finished.getErrorCode());
        assertEquals(1, fixtureGateway.cursor());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void invalidCitationIsRepairedAgainstActualEvidenceWhitelist() {
        fixtureGateway.reset(List.of(CALL_EVIDENCE_JSON, INVALID_CITATION_JSON, VALID_CITATION_JSON));
        HarnessRun run = runService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant",
                1,
                "hash-repair",
                RunType.AGENT_LOOP,
                "引用修复测试",
                "client-replay-repair",
                null,
                null,
                8));

        HarnessRunService.ClaimedRun claimed = runService.claimNext("worker-repair").orElseThrow();
        orchestrator.execute(claimed, () -> {});

        HarnessRun finished = runRepository.findById(run.getRunId()).orElseThrow();
        assertEquals(RunStatus.SUCCEEDED, finished.getStatus());
        assertEquals("已修复引用", finished.getFinalOutputPreview());
        assertEquals(3, finished.getModelCallCount());
        assertEquals(1, stepRepository.findByRunIdOrderBySequenceNoAsc(run.getRunId()).stream()
                .filter(step -> step.getStepType() == StepType.REPAIR)
                .count());
        assertTrue(eventRepository.findByRunIdOrderBySequenceNoAsc(run.getRunId()).stream()
                .anyMatch(event -> "VALIDATION_FAILED".equals(event.getEventType())));
    }

    @TestConfiguration
    static class ReplayTestConfig {

        @Bean
        @Primary
        FixtureReplayModelGateway fixtureReplayModelGateway(HarnessActionParser parser) {
            return new FixtureReplayModelGateway(parser, List.of(CALL_TOOL_JSON, FINAL_JSON), 10, 50);
        }

        @Bean
        @Primary
        ToolRegistry replayToolRegistry(ReplayEchoTool replayEchoTool, EvidenceTestTool evidenceTestTool) {
            DefaultToolRegistry registry = new DefaultToolRegistry(List.of());
            registry.register(replayEchoTool);
            registry.register(evidenceTestTool);
            return registry;
        }

        @Bean
        ReplayEchoTool replayEchoTool() {
            return new ReplayEchoTool();
        }

        @Bean
        EvidenceTestTool evidenceTestTool() {
            return new EvidenceTestTool();
        }

        @Bean
        @Primary
        HarnessDefinitionRegistry replayDefinitionRegistry() {
            return new ReplayDefinitionRegistry();
        }
    }

    static class ReplayDefinitionRegistry extends HarnessDefinitionRegistry {
        @Override
        public java.util.Set<String> allowedTools(String definitionId) {
            java.util.Set<String> tools = new java.util.LinkedHashSet<>(super.allowedTools(definitionId));
            tools.add("echo.test");
            tools.add("evidence.test");
            return tools;
        }

        @Override
        public boolean isToolAllowed(String definitionId, String toolName) {
            return allowedTools(definitionId).contains(toolName);
        }
    }

    static class ReplayEchoTool implements HarnessTool<Object, String> {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("echo.test", "1", "echo", ToolRisk.READ_ONLY, 5000, true, false, 4096);
        }

        @Override
        public Class<Object> inputType() {
            return Object.class;
        }

        @Override
        public ToolResult<String> execute(ToolExecutionContext context, Object input) {
            return ToolResult.ok("ok", "echo:" + input, 1);
        }
    }

    static class EvidenceTestTool implements HarnessTool<Object, Object> {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("evidence.test", "1", "evidence", ToolRisk.READ_ONLY, 5000, true, false, 4096);
        }

        @Override
        public Class<Object> inputType() {
            return Object.class;
        }

        @Override
        public ToolResult<Object> execute(ToolExecutionContext context, Object input) {
            return ToolResult.ok(
                    java.util.Map.of("citations", java.util.List.of(java.util.Map.of(
                            "fileId", "file-1", "chunkIndex", 7, "snippet", "有效证据"))),
                    "accepted=1",
                    1);
        }
    }
}
