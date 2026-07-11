package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    @MockitoSpyBean
    private HarnessToolCallRepository toolCallRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CountingToolArgumentHasher argumentHasher;

    @Autowired
    private CountingCanonicalTool canonicalTool;

    @Autowired
    private HashFailureTestTool hashFailureTool;

    @BeforeEach
    void resetCounters() {
        argumentHasher.reset();
        canonicalTool.reset();
        hashFailureTool.reset();
        clearInvocations(toolCallRepository);
    }

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
        assertEquals(1, argumentHasher.calls());
        assertEquals(argumentHasher.lastHash(), toolCallRepository.findAll().get(0).getArgumentsHash());
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
    void canonicalAutomaticIdempotencyHashesOncePerCallAndExecutesOnce() {
        HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant", 1, "hash", RunType.AGENT_LOOP, "obj", "req-canonical-auto", null, null, 8));
        ToolExecutionContext context = new ToolExecutionContext(
                run.getRunId(), null, null, Set.of(TestConfig.CANONICAL_TEST));
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("query", "制度依据");
        first.put("fileIds", List.of("f1", "f2"));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("fileIds", List.of("f1", "f2"));
        second.put("query", "制度依据");

        ToolResult<?> firstResult = toolExecutor.execute(context, TestConfig.CANONICAL_TEST, first);
        assertTrue(firstResult.success());
        assertEquals(1, argumentHasher.calls());
        String firstHash = argumentHasher.lastHash();

        ToolResult<?> secondResult = toolExecutor.execute(context, TestConfig.CANONICAL_TEST, second);
        assertTrue(secondResult.success());
        assertEquals(2, argumentHasher.calls());
        assertEquals(firstHash, argumentHasher.lastHash());
        assertEquals(1, canonicalTool.executions());

        var calls = toolCallRepository.findAll();
        assertEquals(1, calls.size());
        assertEquals(firstHash, calls.get(0).getArgumentsHash());
        assertEquals(TestConfig.CANONICAL_TEST + ":" + firstHash, calls.get(0).getIdempotencyKey());
    }

    @Test
    void explicitIdempotencyStillHashesExactlyOnceForAudit() {
        HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant", 1, "hash", RunType.AGENT_LOOP, "obj", "req-canonical-explicit", null, null, 8));
        ToolExecutionContext context = new ToolExecutionContext(
                run.getRunId(), null, "  explicit-key  ", Set.of(TestConfig.ECHO_TEST));

        ToolResult<?> result = toolExecutor.execute(context, TestConfig.ECHO_TEST, "hello");

        assertTrue(result.success());
        assertEquals(1, argumentHasher.calls());
        var call = toolCallRepository.findAll().get(0);
        assertEquals("explicit-key", call.getIdempotencyKey());
        assertEquals(argumentHasher.lastHash(), call.getArgumentsHash());
    }

    @Test
    void hashFailureHappensBeforeRepositoryAndToolExecution() {
        HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
                "knowledge-assistant", 1, "hash", RunType.AGENT_LOOP, "obj", "req-hash-failure", null, null, 8));
        ToolExecutionContext context = new ToolExecutionContext(
                run.getRunId(), null, "explicit-failure", Set.of(TestConfig.HASH_FAILURE_TEST));
        HashFailureInput input = new HashFailureInput();
        clearInvocations(toolCallRepository);

        HarnessDomainException exception = assertThrows(
                HarnessDomainException.class,
                () -> toolExecutor.execute(context, TestConfig.HASH_FAILURE_TEST, input));

        assertEquals(HarnessErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertEquals("工具参数无法安全规范化", exception.getMessage());
        assertNull(exception.getCause());
        assertEquals(1, argumentHasher.calls());
        assertEquals(0, hashFailureTool.executions());
        verifyNoInteractions(toolCallRepository);
    }

    @Test
    void temporaryCallWithoutRunIdSkipsHashing() {
        ToolExecutionContext context = new ToolExecutionContext(
                null, null, null, Set.of(TestConfig.HASH_FAILURE_TEST));

        ToolResult<?> result = toolExecutor.execute(
                context, TestConfig.HASH_FAILURE_TEST, new HashFailureInput());

        assertTrue(result.success());
        assertEquals(0, argumentHasher.calls());
        assertEquals(1, hashFailureTool.executions());
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
                new AuditFailureInput("触发固定摘要"));

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
        static final String CANONICAL_TEST = "canonical.test";
        static final String HASH_FAILURE_TEST = "hash.failure.test";

        @Bean
        @Primary
        ToolRegistry toolRegistry(
                EchoTestTool echoTestTool,
                NullResultTool nullResultTool,
                VoidTestTool voidTestTool,
                AuditFailureTestTool auditFailureTestTool,
                CountingCanonicalTool canonicalTool,
                HashFailureTestTool hashFailureTool) {
            DefaultToolRegistry registry = new DefaultToolRegistry(java.util.List.of());
            registry.register(echoTestTool);
            registry.register(nullResultTool);
            registry.register(voidTestTool);
            registry.register(auditFailureTestTool);
            registry.register(canonicalTool);
            registry.register(hashFailureTool);
            return registry;
        }

        @Bean
        CountingToolArgumentHasher toolArgumentHasher() {
            return new CountingToolArgumentHasher();
        }

        @Bean
        ToolArgumentAuditSummarizer toolArgumentAuditSummarizer(ObjectMapper objectMapper) {
            return new TestToolArgumentAuditSummarizer(objectMapper);
        }

        @Bean
        CountingCanonicalTool canonicalTool() {
            return new CountingCanonicalTool();
        }

        @Bean
        HashFailureTestTool hashFailureTool() {
            return new HashFailureTestTool();
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

    record AuditFailureInput(String marker) {
    }

    record CanonicalInput(String query, List<String> fileIds) {
    }

    static final class HashFailureInput {

        public String getSecret() {
            throw new IllegalStateException("密钥-不得泄露");
        }
    }

    static class CountingToolArgumentHasher extends ToolArgumentHasher {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile String lastHash;

        @Override
        public String hash(Object input) {
            calls.incrementAndGet();
            lastHash = super.hash(input);
            return lastHash;
        }

        int calls() {
            return calls.get();
        }

        String lastHash() {
            return lastHash;
        }

        void reset() {
            calls.set(0);
            lastHash = null;
        }
    }

    static class TestToolArgumentAuditSummarizer extends ToolArgumentAuditSummarizer {

        private static final String FALLBACK =
                "{\"schemaVersion\":1,\"summary\":{\"type\":\"unavailable\"}}";

        TestToolArgumentAuditSummarizer(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public String summarize(Class<?> declaredInputType, Object input) {
            if (AuditFailureInput.class.equals(declaredInputType)) {
                return FALLBACK;
            }
            return super.summarize(declaredInputType, input);
        }
    }

    static class CountingCanonicalTool implements HarnessTool<CanonicalInput, String> {

        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    TestConfig.CANONICAL_TEST,
                    "1",
                    "规范化参数测试工具",
                    ToolRisk.READ_ONLY,
                    5000,
                    true,
                    false,
                    4096);
        }

        @Override
        public Class<CanonicalInput> inputType() {
            return CanonicalInput.class;
        }

        @Override
        public ToolResult<String> execute(ToolExecutionContext context, CanonicalInput input) {
            executions.incrementAndGet();
            return ToolResult.ok("done", "canonical:" + input.query(), 1);
        }

        int executions() {
            return executions.get();
        }

        void reset() {
            executions.set(0);
        }
    }

    static class HashFailureTestTool implements HarnessTool<HashFailureInput, String> {

        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    TestConfig.HASH_FAILURE_TEST,
                    "1",
                    "参数哈希失败测试工具",
                    ToolRisk.READ_ONLY,
                    5000,
                    true,
                    false,
                    4096);
        }

        @Override
        public Class<HashFailureInput> inputType() {
            return HashFailureInput.class;
        }

        @Override
        public ToolResult<String> execute(ToolExecutionContext context, HashFailureInput input) {
            executions.incrementAndGet();
            return ToolResult.ok("done", "hash-failure-input-executed", 1);
        }

        int executions() {
            return executions.get();
        }

        void reset() {
            executions.set(0);
        }
    }
}
