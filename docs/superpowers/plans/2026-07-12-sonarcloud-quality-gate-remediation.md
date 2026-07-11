# SonarCloud 质量门禁修复实施计划

> **供自动化执行者使用：** 必须使用 `subagent-driven-development`（推荐）或
> `executing-plans`，按任务顺序逐项实施。所有执行步骤使用复选框跟踪。

**目标：** 通过代码整改关闭 PR #1 当前 8 条日志注入漏洞和 6 条代码味道，将新代码
Security Rating 恢复为 A，并让 SonarCloud Quality Gate 通过。

**架构：** 日志安全采用“删除无运维价值的外部字段 + 在日志调用点直接清洗必须保留的
字符串”的混合策略；业务输入、返回对象、解析元数据、数据库内容和对象键保持不变。
测试侧新增一个自动恢复日志级别并分离 appender 的 Logback 捕获工具，各业务测试只验证
本类产生的格式化日志。

**技术栈：** Java 17、Spring Boot 3.4.1、JUnit 5、Mockito、Logback、Maven、
原生 JavaScript、Node.js、SonarCloud、GitHub CLI。

## 全局约束

- 工作目录固定为 `/Users/suwenfeng/Documents/code/myllm/.worktrees/minio-object-key-integrity`。
- 文档、代码注释和 Git 提交说明全部使用中文。
- 不删除任何文件，不批量删除目录或文件。
- 不修改 SonarCloud Quality Gate、规则级别或扫描范围。
- 不新增 `NOSONAR`、规则抑制、扫描排除或手工“误报”状态。
- 不改变 MinIO 对象键、文件标识、Harness 参数哈希、审计摘要和持久化数据语义。
- `DocumentParseService` 的 CR/LF 替换必须位于日志调用点附近，使用
  `value.replaceAll("[\\r\\n]", "_")`；不得修改业务 `extension`、`failures` 或
  `fallbackReason`。
- 测试中的 Logback appender 必须在 `close()`/`finally` 中分离并恢复原日志级别。
- 每个生产修改先看到相应回归测试或静态检查失败，再做最小实现并复跑验证。

---

### 任务 1：移除 ChatService 日志中的对话正文

**文件：**

- 新建：`src/test/java/com/example/myllm/testing/LogCapture.java`
- 修改：`src/test/java/com/example/myllm/service/ChatServiceTests.java:1-55`
- 修改：`src/main/java/com/example/myllm/service/ChatService.java:31-340`

**接口：**

- 产出：`LogCapture.forClass(Class<?>)`、`events()`、`messages()`、
  `eventStartingWith(String)` 和 `close()`，供后续日志测试复用。
- 产出：`ChatService` 日志只接受固定模型信息、状态、枚举和数值指标，不再接受正文参数。

- [ ] **步骤 1：新增可自动清理的日志捕获工具**

创建完整文件：

```java
package com.example.myllm.testing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender;

    private LogCapture(Class<?> sourceType) {
        logger = (Logger) LoggerFactory.getLogger(sourceType);
        previousLevel = logger.getLevel();
        appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
    }

    public static LogCapture forClass(Class<?> sourceType) {
        return new LogCapture(sourceType);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    public List<String> messages() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    public ILoggingEvent eventStartingWith(String prefix) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}
```

- [ ] **步骤 2：编写 Chat 日志安全回归测试**

在 `ChatServiceTests` 增加这些静态 import 和类型 import：

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.myllm.support.retrieval.QueryIntent;
import com.example.myllm.support.retrieval.QueryRewriteResult;
import com.example.myllm.support.retrieval.QueryRewriteStrategy;
import com.example.myllm.testing.LogCapture;
```

在测试类中增加构造辅助方法和断言辅助方法：

```java
private static ChatService createService(
        ChatModel model,
        RagRetrievalService ragRetrievalService) {
    return new ChatService(
            ChatClient.builder(model).build(),
            mock(TransactionLogService.class),
            mock(RagCallLogService.class),
            mock(FileEmbeddingService.class),
            ragRetrievalService,
            "ollama",
            "mymodel-base",
            "unused",
            0.45);
}

private static void assertLogsExclude(LogCapture logs, String... forbiddenValues) {
    for (String message : logs.messages()) {
        assertFalse(message.contains("\r"), message);
        assertFalse(message.contains("\n"), message);
        for (String forbidden : forbiddenValues) {
            assertFalse(message.contains(forbidden), message);
        }
    }
}
```

增加三个测试：

```java
@Test
void simpleChatLogsOnlyTrustedFields() {
    ChatModel model = prompt ->
            new ChatResponse(List.of(new Generation(new AssistantMessage("连接正常"))));
    ChatService service = createService(model, mock(RagRetrievalService.class));

    try (LogCapture logs = LogCapture.forClass(ChatService.class)) {
        assertEquals("连接正常", service.simpleChat("用户正文\r\nFAKE_WARN"));

        assertEquals(
                "快速对话开始 provider=ollama model=mymodel-base",
                logs.eventStartingWith("快速对话开始").getFormattedMessage());
        assertLogsExclude(logs, "用户正文", "FAKE_WARN");
    }
}

@Test
void chatLogsOnlyTrustedRagDiagnostics() {
    RagRetrievalService retrievalService = mock(RagRetrievalService.class);
    QueryRewriteResult rewrite = new QueryRewriteResult(
            "原始问题\r\nFAKE_ORIGINAL",
            "规范问题\r\nFAKE_NORMALIZED",
            "检索问题\r\nFAKE_RETRIEVAL",
            QueryIntent.SKIP_RAG,
            List.of("秘密文件\r\nFAKE_HINT"),
            true,
            QueryRewriteStrategy.RULE_NORMALIZED);
    when(retrievalService.retrieve(anyString(), anyList()))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                    List.of(),
                    List.of(),
                    rewrite,
                    List.of(),
                    RagRetrievalService.RetrievalDiagnostics.skipped()));
    ChatModel model = prompt -> new ChatResponse(
            List.of(new Generation(new AssistantMessage("模型回答\r\nFAKE_REPLY"))));
    ChatService service = createService(model, retrievalService);

    try (LogCapture logs = LogCapture.forClass(ChatService.class)) {
        service.chat(
                "用户问题\r\nFAKE_MESSAGE",
                "系统提示\r\nFAKE_SYSTEM",
                true,
                List.of(),
                "POST");

        assertEquals(
                "RAG 跳过 useRag=true intent=SKIP_RAG",
                logs.eventStartingWith("RAG 跳过").getFormattedMessage());
        assertEquals(
                "RAG 问题改写 intent=SKIP_RAG strategy=RULE_NORMALIZED",
                logs.eventStartingWith("RAG 问题改写").getFormattedMessage());
        assertEquals(
                "调用本地模型开始 provider=ollama model=mymodel-base useRag=true",
                logs.eventStartingWith("调用本地模型开始").getFormattedMessage());
        assertFalse(logs.eventStartingWith("调用本地模型完成")
                .getFormattedMessage()
                .contains("reply="));
        assertLogsExclude(
                logs,
                "原始问题",
                "规范问题",
                "检索问题",
                "秘密文件",
                "用户问题",
                "系统提示",
                "模型回答",
                "FAKE_");
    }
}

@Test
void chatRetrievalFailureLogOmitsUserContentAndThrowable() {
    RagRetrievalService retrievalService = mock(RagRetrievalService.class);
    when(retrievalService.retrieve(anyString(), anyList()))
            .thenThrow(new IllegalStateException("检索失败\r\nFAKE_ERROR"));
    ChatModel model = prompt -> new ChatResponse(
            List.of(new Generation(new AssistantMessage("回退回答\r\nFAKE_REPLY"))));
    ChatService service = createService(model, retrievalService);

    try (LogCapture logs = LogCapture.forClass(ChatService.class)) {
        service.chat(
                "失败场景用户问题\r\nFAKE_MESSAGE",
                "失败场景系统提示\r\nFAKE_SYSTEM",
                true,
                List.of(),
                "POST");

        var failure = logs.eventStartingWith("RAG 检索失败");
        assertEquals(
                "RAG 检索失败，回退为普通对话 errorType=IllegalStateException",
                failure.getFormattedMessage());
        assertNull(failure.getThrowableProxy());
        assertLogsExclude(
                logs,
                "失败场景用户问题",
                "失败场景系统提示",
                "回退回答",
                "FAKE_");
    }
}
```

- [ ] **步骤 3：运行测试并确认旧日志使其失败**

运行：

```bash
mvn -q -Dtest='ChatServiceTests#simpleChatLogsOnlyTrustedFields+chatLogsOnlyTrustedRagDiagnostics+chatRetrievalFailureLogOmitsUserContentAndThrowable' test
```

预期：3 个测试失败；失败证据分别是快速对话正文、RAG/模型正文仍在日志中，以及 RAG
WARN 仍携带 throwable。

- [ ] **步骤 4：实施最小生产修改**

删除 `LOG_PREVIEW_LEN` 常量和整个 `preview(String)` 方法，并将调用点改为：

```java
logRagSkipped(rewrite);

log.warn("RAG 检索失败，回退为普通对话 errorType={}",
        e.getClass().getSimpleName());

logModelStart(useRag);
logModelSuccess(metrics);

logSimpleStart();
```

完整替换相关日志辅助方法：

```java
private void logRagSkipped(QueryRewriteResult rewrite) {
    if (log.isInfoEnabled()) {
        log.info("RAG 跳过 useRag=true intent={}", rewrite.intent());
    }
}

private void logRewrite(QueryRewriteResult rewrite) {
    if (rewrite != null && log.isInfoEnabled()) {
        log.info("RAG 问题改写 intent={} strategy={}",
                rewrite.intent(), rewrite.strategy());
    }
}

private void logModelStart(boolean useRag) {
    if (log.isInfoEnabled()) {
        log.info("调用本地模型开始 provider={} model={} useRag={}",
                provider, model, useRag);
    }
}

private void logModelSuccess(InferenceMetrics metrics) {
    if (log.isInfoEnabled()) {
        log.info("调用本地模型完成 provider={} model={} elapsedMs={} inputTokens={} outputTokens={} totalTokens={} inferenceSpeedTps={} evalDurationMs={}",
                provider, model, metrics.wallClockDurationMs(), metrics.inputTokens(),
                metrics.outputTokens(), metrics.totalTokens(), formatSpeed(metrics.inferenceSpeedTps()),
                metrics.evalDurationMs());
    }
}

private void logSimpleStart() {
    if (log.isInfoEnabled()) {
        log.info("快速对话开始 provider={} model={}", provider, model);
    }
}
```

`logRagMiss`、`logRagApplied`、`logSimpleSuccess`、`formatSpeed` 和 `rootCauseMessage`
保持不变。

- [ ] **步骤 5：运行定向测试和 Chat 完整测试**

```bash
mvn -q -Dtest='ChatServiceTests#simpleChatLogsOnlyTrustedFields+chatLogsOnlyTrustedRagDiagnostics+chatRetrievalFailureLogOmitsUserContentAndThrowable' test
mvn -q -Dtest=ChatServiceTests test
```

预期：定向测试 3/3 通过；`ChatServiceTests` 5/5 通过。

- [ ] **步骤 6：提交 Chat 日志修复**

```bash
git add src/test/java/com/example/myllm/testing/LogCapture.java \
  src/test/java/com/example/myllm/service/ChatServiceTests.java \
  src/main/java/com/example/myllm/service/ChatService.java
git commit -m "安全：移除对话日志中的不可信内容"
```

---

### 任务 2：仅在 DocumentParse 日志边界清洗换行

**文件：**

- 修改：`src/test/java/com/example/myllm/service/DocumentParseServiceTests.java:1-180`
- 修改：`src/main/java/com/example/myllm/service/DocumentParseService.java:211-245`

**接口：**

- 消费：任务 1 的 `LogCapture`。
- 产出：日志使用单行副本；解析器输入、`fallbackReason`、解析元数据和返回对象继续使用原值。

- [ ] **步骤 1：新增扩展名与失败原因回归测试**

增加 import：

```java
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.myllm.support.document.DocumentParseResult;
import com.example.myllm.testing.LogCapture;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.multipart.MultipartFile;
```

增加单行断言辅助方法：

```java
private static void assertSingleLine(String message) {
    assertFalse(message.contains("\r"), message);
    assertFalse(message.contains("\n"), message);
}
```

增加两个测试：

```java
@Test
void autoParseSanitizesExtensionOnlyInLogs() {
    String rawExtension = "safe\r\nforged";
    AtomicReference<String> receivedExtension = new AtomicReference<>();
    LocalDocumentParser localParser = new LocalDocumentParser() {
        @Override
        public boolean supports(String extension) {
            receivedExtension.set(extension);
            return true;
        }

        @Override
        public ParsedDocument parse(MultipartFile file) {
            return new ParsedDocument(
                    file.getOriginalFilename(),
                    List.of(new DocumentBlock(
                            DocumentBlockType.PARAGRAPH, "content", null, 0)),
                    Map.of("extension", receivedExtension.get()));
        }
    };
    DocumentParseService service = createService(localParser, List.of());
    MockMultipartFile file = new MockMultipartFile(
            "file",
            "report." + rawExtension,
            "application/octet-stream",
            "content".getBytes());

    try (LogCapture logs = LogCapture.forClass(DocumentParseService.class)) {
        DocumentParseResult result = service.parse(file, "auto");

        assertEquals(rawExtension, receivedExtension.get());
        assertEquals(rawExtension, result.document().metadata().get("extension"));
        String success = logs.eventStartingWith("自动解析完成").getFormattedMessage();
        assertEquals(
                "自动解析完成 requested=auto applied=local extension=safe__forged attempted=[local] fallbackReason=null",
                success);
        assertSingleLine(success);
    }
}

@Test
void autoParseSanitizesFailureLogsWithoutChangingFallbackReason() {
    DocForgeRemoteDocumentParser makerParser =
            new DocForgeRemoteDocumentParser(null, "maker", "maker") {
                @Override
                public boolean supports(String extension) {
                    return "pdf".equals(extension);
                }

                @Override
                public ParsedDocument parse(MultipartFile file) {
                    throw new DocForgeServiceException("maker model\r\nunavailable", 503);
                }
            };
    DocForgeRemoteDocumentParser doclingParser =
            new DocForgeRemoteDocumentParser(null, "docling", "docling") {
                @Override
                public boolean supports(String extension) {
                    return "pdf".equals(extension);
                }

                @Override
                public ParsedDocument parse(MultipartFile file) {
                    return new ParsedDocument(
                            "report.pdf",
                            List.of(new DocumentBlock(
                                    DocumentBlockType.PARAGRAPH,
                                    "docling fallback",
                                    null,
                                    0)),
                            Map.of(
                                    "extension", "pdf",
                                    "parser", "docling",
                                    "parsePages", 4));
                }
            };
    DocumentParseService service =
            createService(new LocalDocumentParser(), List.of(makerParser, doclingParser));
    MockMultipartFile file = new MockMultipartFile(
            "file", "report.pdf", "application/pdf", "%PDF-1.4".getBytes());

    try (LogCapture logs = LogCapture.forClass(DocumentParseService.class)) {
        DocumentParseResult result = service.parse(file, "auto");

        String rawFallbackReason = "maker: maker model\r\nunavailable";
        assertEquals(rawFallbackReason, result.fallbackReason());
        assertEquals(rawFallbackReason, result.document().metadata().get("parseFallbackReason"));

        String warning = logs.eventStartingWith("自动解析候选失败").getFormattedMessage();
        assertEquals(
                "自动解析候选失败，将尝试下一模式 mode=maker extension=pdf reason=maker model__unavailable",
                warning);
        assertSingleLine(warning);

        String success = logs.eventStartingWith("自动解析完成").getFormattedMessage();
        assertEquals(
                "自动解析完成 requested=auto applied=docling extension=pdf attempted=[maker, docling] fallbackReason=maker: maker model__unavailable",
                success);
        assertSingleLine(success);
    }
}
```

- [ ] **步骤 2：运行测试并确认日志断言失败、业务值断言通过**

```bash
mvn -q -Dtest='DocumentParseServiceTests#autoParseSanitizesExtensionOnlyInLogs+autoParseSanitizesFailureLogsWithoutChangingFallbackReason' test
```

预期：2 个测试失败，失败仅来自格式化日志仍包含 CR/LF；原始扩展名、返回
`fallbackReason` 和 `parseFallbackReason` 断言保持通过。

- [ ] **步骤 3：在日志调用点创建安全副本**

成功路径替换为：

```java
String fallbackReason = failures.isEmpty() ? null : String.join("; ", failures);
ParsedDocument enriched = enrichParseMetadata(
        document, "auto", appliedMode, attempted, fallbackReason);

String loggedExtension = extension.replaceAll("[\\r\\n]", "_");
String loggedFallbackReason = fallbackReason == null
        ? null
        : fallbackReason.replaceAll("[\\r\\n]", "_");
log.info("自动解析完成 requested=auto applied={} extension={} attempted={} fallbackReason={}",
        appliedMode, loggedExtension, attempted, loggedFallbackReason);

return DocumentParseResult.from(
        enriched, "auto", appliedMode, durationMs, attempted, fallbackReason);
```

失败路径替换为：

```java
} catch (RuntimeException e) {
    lastFailure = e;
    String failureReason = conciseMessage(e);
    failures.add(mode + ": " + failureReason);
    if (log.isWarnEnabled()) {
        String loggedExtension = extension.replaceAll("[\\r\\n]", "_");
        String loggedFailureReason = failureReason.replaceAll("[\\r\\n]", "_");
        log.warn("自动解析候选失败，将尝试下一模式 mode={} extension={} reason={}",
                mode, loggedExtension, loggedFailureReason);
    }
}
```

不得修改 `conciseMessage()`。

- [ ] **步骤 4：运行定向测试和解析完整测试**

```bash
mvn -q -Dtest='DocumentParseServiceTests#autoParseSanitizesExtensionOnlyInLogs+autoParseSanitizesFailureLogsWithoutChangingFallbackReason' test
mvn -q -Dtest=DocumentParseServiceTests test
```

预期：定向测试 2/2 通过；`DocumentParseServiceTests` 9/9 通过。

- [ ] **步骤 5：提交解析日志修复**

```bash
git add src/test/java/com/example/myllm/service/DocumentParseServiceTests.java \
  src/main/java/com/example/myllm/service/DocumentParseService.java
git commit -m "安全：清洗文档解析日志字段"
```

---
### 任务 3：收紧 FileEmbeddingService 完成日志

**文件：**

- 新建：`src/test/java/com/example/myllm/service/FileEmbeddingServiceTests.java`
- 修改：`src/main/java/com/example/myllm/service/FileEmbeddingService.java:152-326`

**接口：**

- 消费：任务 1 的 `LogCapture`。
- 产出：向量化完成日志保留 `fileId`、固定模式、清理/分块指标和耗时；API 返回与写入请求
  继续保留原文件信息。

- [ ] **步骤 1：新增完整日志隐私回归测试**

创建完整测试文件：

```java
package com.example.myllm.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.myllm.config.GraphProperties;
import com.example.myllm.support.chunking.ChunkStrategy;
import com.example.myllm.support.chunking.ChunkingResult;
import com.example.myllm.support.chunking.DocumentChunk;
import com.example.myllm.support.chunking.DocumentChunkFactory;
import com.example.myllm.support.document.CleanedDocument;
import com.example.myllm.support.document.CleaningReport;
import com.example.myllm.support.document.DocumentBlock;
import com.example.myllm.support.document.DocumentBlockType;
import com.example.myllm.support.document.DocumentParseResult;
import com.example.myllm.support.document.ParseProgressListener;
import com.example.myllm.support.document.ParsedDocument;
import com.example.myllm.support.upload.UploadProgressReporter;
import com.example.myllm.testing.LogCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

class FileEmbeddingServiceTests {

    @Test
    void embeddingCompletionLogOmitsExternalDocumentData() {
        String content = "可向量化正文";
        DocumentBlock block = new DocumentBlock(
                DocumentBlockType.PARAGRAPH, content, null, 0);
        ParsedDocument parsedDocument = new ParsedDocument(
                "secret-file.pdf",
                List.of(block),
                Map.of("extension", "secret-content-type"));
        DocumentParseResult parseResult = new DocumentParseResult(
                parsedDocument,
                "auto",
                "docling",
                "secret-parser-engine",
                1,
                12L,
                List.of("docling"),
                null);
        CleaningReport cleaningReport = new CleaningReport(
                "test-cleaner",
                content.length(),
                content.length(),
                0,
                0.0,
                0,
                Map.of(),
                List.of("secret-cleaning-warning"));
        CleanedDocument cleanedDocument = new CleanedDocument(
                List.of(block), content, content, cleaningReport);
        DocumentChunk chunk = DocumentChunkFactory.of(
                0, content, "secret-heading", 0, 0, Map.of());
        ChunkingResult chunkingResult = new ChunkingResult(
                ChunkStrategy.FIXED, ChunkStrategy.FIXED, List.of(chunk));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "secret-file.pdf",
                "application/pdf",
                content.getBytes(StandardCharsets.UTF_8));

        JdbcTemplate vectorJdbcTemplate = mock(JdbcTemplate.class);
        DocumentCleaningService cleaningService = mock(DocumentCleaningService.class);
        DocumentCleaningLogService cleaningLogService = mock(DocumentCleaningLogService.class);
        DocumentChunkingService chunkingService = mock(DocumentChunkingService.class);
        DocumentParseService parseService = mock(DocumentParseService.class);
        DocumentStorageService storageService = mock(DocumentStorageService.class);
        VectorChunkBatchWriter batchWriter = mock(VectorChunkBatchWriter.class);

        when(parseService.parse(
                eq(file),
                eq("auto"),
                any(ParseProgressListener.class)))
                .thenReturn(parseResult);
        when(storageService.isEnabled()).thenReturn(false);
        when(cleaningService.clean(parsedDocument)).thenReturn(cleanedDocument);
        when(chunkingService.chunk(cleanedDocument, "fixed")).thenReturn(chunkingResult);
        when(batchWriter.writeAll(
                any(VectorChunkBatchWriter.WriteRequest.class),
                eq(List.of(chunk)),
                any(VectorChunkBatchWriter.ProgressCallback.class)))
                .thenReturn(3);

        FileEmbeddingService service = new FileEmbeddingService(
                vectorJdbcTemplate,
                mock(EmbeddingModel.class),
                cleaningService,
                cleaningLogService,
                chunkingService,
                parseService,
                storageService,
                mock(GraphIndexTaskService.class),
                new GraphProperties(),
                batchWriter,
                mock(FileIngestionCleanupService.class),
                new ObjectMapper(),
                "file_embeddings",
                5,
                false,
                16,
                64);

        try (LogCapture logs = LogCapture.forClass(FileEmbeddingService.class)) {
            service.embedAndStoreWithProgress(
                    file, "fixed", "auto", UploadProgressReporter.noop());

            String completionLog = logs.eventStartingWith("文件清理及向量化完成")
                    .getFormattedMessage();
            for (String sensitiveValue : List.of(
                    "secret-file.pdf",
                    "secret-content-type",
                    "secret-parser-engine",
                    "secret-heading",
                    "secret-cleaning-warning")) {
                assertFalse(
                        completionLog.contains(sensitiveValue),
                        () -> "完成日志仍包含外部数据: " + sensitiveValue);
            }
        }
    }
}
```

- [ ] **步骤 2：运行测试并确认旧日志暴露外部字段**

```bash
mvn -q -Dtest=FileEmbeddingServiceTests test
```

预期：1 个测试失败，格式化完成日志仍包含 `secret-file.pdf` 等外部数据。

- [ ] **步骤 3：删除只为日志生成的标题样本并收窄日志字段**

删除整个 `headingSample` 局部计算：

```java
String headingSample = chunks.stream()
        .map(DocumentChunk::headingPath)
        .filter(path -> path != null && !path.isBlank())
        .findFirst()
        .orElse("(none)");
```

将完成日志替换为：

```java
if (log.isInfoEnabled()) {
    log.info("文件清理及向量化完成 fileId={} parseMode={} cleanerVersion={} requestedStrategy={} appliedStrategy={} rawChars={} cleanedChars={} removalRatio={} duplicateBlocks={} chunks={} dimension={} parseDurationMs={} storingMs={} cleaningMs={} chunkingMs={} embeddingMs={} pipelineMs={} graphEnqueued={}",
            fileId, parseResult.parseMode(), cleaningReport.cleanerVersion(),
            chunkingResult.requestedStrategy().apiValue(),
            chunkingResult.appliedStrategy().apiValue(),
            cleaningReport.rawCharCount(), cleaningReport.cleanedCharCount(),
            String.format("%.3f", cleaningReport.removalRatio()),
            cleaningReport.removedDuplicateBlocks(), chunks.size(), embeddingDimension,
            parseResult.parseDurationMs(), storingDurationMs, cleaningDurationMs,
            chunkingDurationMs, embeddingDurationMs, pipelineDurationMs,
            graphIndexEnqueued);
}
```

`contentType` 仍用于 `VectorChunkBatchWriter.WriteRequest`，不得删除。`fileName` 仍用于返回对象、
清洗日志存储和图索引任务，也不得删除。

- [ ] **步骤 4：运行定向测试**

```bash
mvn -q -Dtest=FileEmbeddingServiceTests test
```

预期：1/1 通过。

- [ ] **步骤 5：提交向量化日志修复**

```bash
git add src/test/java/com/example/myllm/service/FileEmbeddingServiceTests.java \
  src/main/java/com/example/myllm/service/FileEmbeddingService.java
git commit -m "安全：收紧文档向量化完成日志"
```

---

### 任务 4：清理 DocForge 日志与 multipart 文件名

**文件：**

- 新建：`src/test/java/com/example/myllm/support/docforge/DocForgeClientTests.java`
- 修改：`src/main/java/com/example/myllm/support/docforge/DocForgeClient.java:206-277,438-451`

**接口：**

- 产出：包可见静态方法 `DocForgeClient.sanitizeFilename(String)`；仅供同包测试和 multipart
  请求构造使用。
- 产出：同步/异步运行日志只记录白名单 engine 和失败状态，不记录 file/size。

- [ ] **步骤 1：新增文件名清洗测试**

创建完整测试文件：

```java
package com.example.myllm.support.docforge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DocForgeClientTests {

    @Test
    void sanitizeFilenameFallsBackForMissingName() {
        assertEquals("unknown", DocForgeClient.sanitizeFilename(null));
        assertEquals("unknown", DocForgeClient.sanitizeFilename("  "));
    }

    @Test
    void sanitizeFilenameStripsPathAndReplacesLineBreaks() {
        assertEquals(
                "report__forged.pdf",
                DocForgeClient.sanitizeFilename(
                        "C:\\uploads\\report\r\nforged.pdf"));
    }
}
```

- [ ] **步骤 2：运行测试并确认旧实现不可访问/未清洗**

```bash
mvn -q -Dtest=DocForgeClientTests test
```

预期：先因 `sanitizeFilename(String)` 是 `private` 而测试编译失败；若只调整可见性，则第二个
测试因旧值仍包含 CR/LF 而失败。

- [ ] **步骤 3：实施文件名清洗和日志字段移除**

将三个日志点替换为：

```java
log.warn(
        "DocForge 同步解析失败(status={})，改走异步: engine={}",
        e.statusCode(),
        docForgeEngine);
```

```java
log.info("DocForge 同步解析: engine={}", engine);
```

```java
log.info("DocForge 异步解析: engine={}", engine);
```

`parseSync` 和 `parseAsync` 中的 `fileName` 局部变量仍用于 IOException 业务异常，必须保留。

将文件名清洗方法替换为：

```java
static String sanitizeFilename(String fileName) {
    if (fileName == null || fileName.isBlank()) {
        return UNKNOWN;
    }
    String normalized = fileName.replace('\\', '/');
    int slash = normalized.lastIndexOf('/');
    String leaf = slash >= 0 ? normalized.substring(slash + 1) : normalized;
    String sanitized = leaf.replaceAll("[\\r\\n]", "_");
    return sanitized.isBlank() ? UNKNOWN : sanitized;
}
```

- [ ] **步骤 4：运行 DocForge 定向测试**

```bash
mvn -q -Dtest=DocForgeClientTests test
rg -n 'DocForge .*file=|DocForge .*size=' src/main/java/com/example/myllm/support/docforge/DocForgeClient.java
```

预期：2/2 通过；`rg` 无输出。

- [ ] **步骤 5：提交 DocForge 修复**

```bash
git add src/test/java/com/example/myllm/support/docforge/DocForgeClientTests.java \
  src/main/java/com/example/myllm/support/docforge/DocForgeClient.java
git commit -m "安全：清理 DocForge 文件名日志"
```

---

### 任务 5：移除 MinIO 写入日志中的大小字段

**文件：**

- 修改：`src/test/java/com/example/myllm/support/minio/MinioStorageServiceTests.java:1-75`
- 修改：`src/main/java/com/example/myllm/support/minio/MinioStorageService.java:47-84`

**接口：**

- 消费：任务 1 的 `LogCapture`。
- 产出：成功日志保留 bucket/object；`MinioStoredObject.sizeBytes()` 和上传内容保持不变。

- [ ] **步骤 1：给现有两条存储测试增加日志与大小断言**

增加 import：

```java
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.myllm.testing.LogCapture;
```

将原始文件测试主体替换为：

```java
MockMultipartFile file = new MockMultipartFile(
        "file", "docs/report.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8));

try (LogCapture logs = LogCapture.forClass(MinioStorageService.class)) {
    MinioStoredObject stored = service.storeOriginal(FILE_ID, file, STORAGE_DATE);

    ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
    verify(minioClient).putObject(args.capture());
    assertEquals(
            "uploads/2026-07-02/report__550e8400-e29b-41d4-a716-446655440000.pdf",
            args.getValue().object());
    assertEquals("report__550e8400-e29b-41d4-a716-446655440000.pdf", stored.fileName());
    assertEquals(3L, stored.sizeBytes());
    assertFalse(logs.eventStartingWith("原始文件已写入 MinIO")
            .getFormattedMessage()
            .contains("size="));
}
```

将解析文件测试主体替换为：

```java
try (LogCapture logs = LogCapture.forClass(MinioStorageService.class)) {
    MinioStoredObject stored = service.storeParsed(
            FILE_ID, "docs/report.pdf", "body", STORAGE_DATE);

    ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
    verify(minioClient).putObject(args.capture());
    assertEquals(
            "parsed/2026-07-02/report__550e8400-e29b-41d4-a716-446655440000.parsed.md",
            args.getValue().object());
    assertEquals(
            "report__550e8400-e29b-41d4-a716-446655440000.parsed.md",
            stored.fileName());
    assertEquals(4L, stored.sizeBytes());
    assertFalse(logs.eventStartingWith("解析文件已写入 MinIO")
            .getFormattedMessage()
            .contains("size="));
}
```

- [ ] **步骤 2：运行测试并确认日志断言失败**

```bash
mvn -q -Dtest=MinioStorageServiceTests test
```

预期：2 个测试因旧日志仍含 `size=` 而失败；对象键、文件名和 `sizeBytes()` 断言通过。

- [ ] **步骤 3：移除两个成功日志的大小参数**

```java
log.info(
        "原始文件已写入 MinIO bucket={} object={}",
        properties.bucket(),
        objectPath);
```

```java
log.info(
        "解析文件已写入 MinIO bucket={} object={}",
        properties.bucket(),
        objectPath);
```

两个 `new MinioStoredObject(..., bytes.length)` 返回语句保持不变。

- [ ] **步骤 4：运行存储定向测试**

```bash
mvn -q -Dtest=MinioStorageServiceTests test
```

预期：2/2 通过。

- [ ] **步骤 5：提交 MinIO 日志修复**

```bash
git add src/test/java/com/example/myllm/support/minio/MinioStorageServiceTests.java \
  src/main/java/com/example/myllm/support/minio/MinioStorageService.java
git commit -m "安全：移除 MinIO 写入大小日志"
```

---

### 任务 6：修复 5 条 Java 代码味道并同步代码镜像

**文件：**

- 修改：`src/main/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizer.java:23-170`
- 修改：`src/main/java/com/example/myllm/harness/application/ToolArgumentHasher.java:176-200`
- 修改：`src/test/java/com/example/myllm/harness/application/ToolArgumentHasherTests.java:300-310`
- 修改：`src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java:168-185`
- 修改：`src/test/java/com/example/myllm/service/DocumentStorageServiceTests.java:80-90`
- 修改：`docs/superpowers/plans/2026-07-11-harness-tool-argument-hash.md:261-270,587-610,1066-1084`
- 修改：`docs/superpowers/plans/2026-07-10-minio-object-key-integrity.md:477-486`
- 修改：`docs/superpowers/plans/2026-07-10-harness-tool-argument-audit.md:239-248`

**接口：**

- 产出：审计摘要 JSON、哈希输出和异常边界保持不变。
- 产出：异常断言 lambda 只保留一个可能抛出运行时异常的调用。

- [ ] **步骤 1：记录静态问题基线并运行行为基线测试**

```bash
rg -n '"string"' src/main/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizer.java
rg -n 'Object record\b|record\.getClass|enterPath\(record\b|canAccess\(record\b|invoke\(record\b|remove\(record\b' \
  src/main/java/com/example/myllm/harness/application/ToolArgumentHasher.java
mvn -q -Dtest=ToolArgumentAuditSummarizerTests,ToolArgumentHasherTests,ToolExecutorTests,DocumentStorageServiceTests test
```

预期：第一条命令显示 4 个重复 `"string"`；第二条命令显示受限标识符参数及其引用；
46 个既有测试通过。这里的红灯来自 Sonar 规则而非业务断言。

- [ ] **步骤 2：提取审计摘要字符串类型常量**

在常量区增加：

```java
private static final String TYPE_STRING = "string";
```

四处统一改为：

```java
ObjectNode summary = typeNode(TYPE_STRING);
```

```java
return TYPE_STRING;
```

具体替换范围是 `summarizeNode` 的两个 `typeNode("string")`，以及 `nodeType` 的两个
`return "string"`。

- [ ] **步骤 3：重命名哈希器的受限标识符参数**

完整替换 `writeRecord`：

```java
private static void writeRecord(
        JsonGenerator generator,
        Object recordValue,
        int depth,
        NodeBudget budget,
        IdentityHashMap<Object, Boolean> path) throws ReflectiveOperationException, IOException {
    RecordComponent[] components = recordValue.getClass().getRecordComponents();
    budget.ensureChildren(components.length);
    Arrays.sort(components, Comparator.comparing(RecordComponent::getName));
    enterPath(recordValue, path);
    try {
        generator.writeStartObject();
        for (RecordComponent component : components) {
            Method accessor = component.getAccessor();
            if (!accessor.canAccess(recordValue) && !accessor.trySetAccessible()) {
                throw new HashingFailure();
            }
            generator.writeFieldName(component.getName());
            writeValue(generator, accessor.invoke(recordValue), depth + 1, budget, path);
        }
        generator.writeEndObject();
    } finally {
        path.remove(recordValue);
    }
}
```

- [ ] **步骤 4：收窄三条 assertThrows lambda**

`ToolArgumentHasherTests` 改为：

```java
@Test
void rejectsThrowingRecordWithoutLeakingSecret() {
    ThrowingRecord input = new ThrowingRecord("密钥-不得泄露");

    HarnessDomainException exception = assertThrows(
            HarnessDomainException.class,
            () -> hasher.hash(input));

    assertEquals(HarnessErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    assertEquals("工具参数无法安全规范化", exception.getMessage());
    assertFalse(exception.getMessage().contains("密钥-不得泄露"));
    assertNull(exception.getCause());
}
```

`ToolExecutorTests` 改为：

```java
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
```

`DocumentStorageServiceTests` 改为：

```java
@Test
void rejectsUnsafeFileIdBeforeAnyStorageCall() {
    MockMultipartFile file = sampleFile();
    DocumentParseResult parseResult = sampleParseResult();
    ParsedDocument document = parseResult.document();

    assertThrows(
            IllegalArgumentException.class,
            () -> service.store("../shared", "report.pdf", file, document, parseResult));

    verifyNoInteractions(minioStorageService, repository);
}
```

- [ ] **步骤 5：同步既有实施计划中的完整代码镜像**

在 `2026-07-11-harness-tool-argument-hash.md` 中同步任务 6 步骤 3、步骤 4 的
`recordValue`、`ThrowingRecord input` 和 `HashFailureInput input` 完整代码。

在 `2026-07-10-minio-object-key-integrity.md` 中将对应测试镜像改为：

```java
MockMultipartFile file = sampleFile();
DocumentParseResult parseResult = sampleParseResult();
ParsedDocument document = parseResult.document();

assertThrows(
        IllegalArgumentException.class,
        () -> service.store("../shared", "report.pdf", file, document, parseResult));
```

在 `2026-07-10-harness-tool-argument-audit.md` 的常量代码块加入：

```java
private static final String TYPE_STRING = "string";
```

并将“文本节点只写 `type=string` 和 `length`”补充为“文本类型统一复用
`TYPE_STRING`，摘要 JSON 仍输出 `type=string` 和 `length`”。

- [ ] **步骤 6：运行静态复查和定向测试**

```bash
rg -n 'Object record\b|record\.getClass|enterPath\(record\b|canAccess\(record\b|invoke\(record\b|remove\(record\b' \
  src/main/java/com/example/myllm/harness/application/ToolArgumentHasher.java
rg -n '"string"' src/main/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizer.java
rg -n 'hasher\.hash\(new ThrowingRecord|execute\([^\n]*new HashFailureInput|service\.store\([^\n]*parseResult\.document' \
  src/test/java docs/superpowers/plans
mvn -q -Dtest=ToolArgumentAuditSummarizerTests,ToolArgumentHasherTests,ToolExecutorTests,DocumentStorageServiceTests test
```

预期：第一、第三条 `rg` 无输出；第二条仅显示 `TYPE_STRING` 常量中的一个字符串字面量；
46 个测试通过。

- [ ] **步骤 7：提交 Java 代码味道修复**

```bash
git add src/main/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizer.java \
  src/main/java/com/example/myllm/harness/application/ToolArgumentHasher.java \
  src/test/java/com/example/myllm/harness/application/ToolArgumentHasherTests.java \
  src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java \
  src/test/java/com/example/myllm/service/DocumentStorageServiceTests.java \
  docs/superpowers/plans/2026-07-11-harness-tool-argument-hash.md \
  docs/superpowers/plans/2026-07-10-minio-object-key-integrity.md \
  docs/superpowers/plans/2026-07-10-harness-tool-argument-audit.md
git commit -m "重构：修复工具参数 Sonar 代码味道"
```

---

### 任务 7：用对象展开合并评测请求头

**文件：**

- 修改：`src/main/resources/static/eval.html:618-631`

**接口：**

- 产出：`api(path, opts)` 继续先提供默认 JSON Content-Type，再允许普通对象 headers 以同名键
  覆盖默认值。
- 非目标：不扩展 `Headers` 实例、tuple 数组或大小写不敏感键的现有行为。

- [ ] **步骤 1：记录 S6661 静态红灯并验证新旧合并语义**

```bash
rg -n 'Object\.assign' src/main/resources/static/eval.html
node --input-type=module -e '
import assert from "node:assert/strict";
const oldHeaders = options =>
  Object.assign({"Content-Type":"application/json"}, options?.headers);
const newHeaders = options => ({
  "Content-Type":"application/json",
  ...options?.headers
});
const cases = [
  undefined,
  null,
  {},
  {headers: undefined},
  {headers: null},
  {headers: {"Content-Type":"text/plain"}},
  {headers: {"X-Trace":"trace-1"}},
  {headers: {"Content-Type":"text/plain", "X-Trace":"trace-1"}}
];
for (const options of cases) {
  assert.deepEqual(newHeaders(options), oldHeaders(options));
}
console.log("header merge matrix OK");'
```

预期：`rg` 命中当前 S6661 位置；Node 输出 `header merge matrix OK`。

- [ ] **步骤 2：替换为对象展开**

```javascript
const res = await fetch(path, {
  ...requestOptions,
  headers: {
    'Content-Type': 'application/json',
    ...requestOptions?.headers
  }
});
```

- [ ] **步骤 3：运行静态、语义和模块语法验证**

```bash
rg -n 'Object\.assign' src/main/resources/static/eval.html
node --input-type=module -e '
import assert from "node:assert/strict";
const oldHeaders = options =>
  Object.assign({"Content-Type":"application/json"}, options?.headers);
const newHeaders = options => ({
  "Content-Type":"application/json",
  ...options?.headers
});
const cases = [
  undefined,
  null,
  {},
  {headers: undefined},
  {headers: null},
  {headers: {"Content-Type":"text/plain"}},
  {headers: {"X-Trace":"trace-1"}},
  {headers: {"Content-Type":"text/plain", "X-Trace":"trace-1"}}
];
for (const options of cases) {
  assert.deepEqual(newHeaders(options), oldHeaders(options));
}
console.log("header merge matrix OK");'
sed -n '/<script type="module">/,/<\/script>/p' src/main/resources/static/eval.html \
  | sed '1d;$d' \
  | node --check --input-type=module
```

预期：第一条 `rg` 无输出；语义矩阵输出 `header merge matrix OK`；模块语法检查退出码为 0
且无输出。

- [ ] **步骤 4：提交前端代码味道修复**

```bash
git add src/main/resources/static/eval.html
git commit -m "重构：使用对象展开合并评测请求头"
```

---

### 任务 8：落地日志安全规范并执行完整本地验证

**文件：**

- 修改：`docs/code-quality-guidelines.md:5-10`

**接口：**

- 产出：后续开发可直接执行的日志安全准则。
- 验证：所有新增/既有测试、文档链接、JavaScript 语法和 Git 差异检查全部通过。

- [ ] **步骤 1：补充日志安全规范**

在“安全与配置”下追加：

```markdown
- 用户输入、上传文件名、外部响应、模型输出和异常消息均视为不可信数据，禁止原样写入日志。
- INFO/WARN 日志优先记录内部标识、固定状态、枚举、数值指标和耗时，不记录正文或标题样本。
- 确需记录的外部字符串必须在日志调用边界显式清除 CR/LF 并限制长度；业务值和持久化值保持原样。
- 禁止使用 `NOSONAR`、规则抑制、扫描排除或降低 Quality Gate 标准掩盖日志注入问题。
```

在“Java 编码规范”下追加：

```markdown
- 语义固定且重复出现的字符串字面量应提取为类级常量，避免多处分叉。
- 参数和局部变量不得使用 `record` 等 Java 受限标识符，应使用能表达职责的名称。
```

在“HTML / JavaScript 规范”下追加：

```markdown
- 合并普通对象优先使用对象展开；默认值先声明、调用方值后展开，以保持覆盖顺序。
```

在“提交前检查”下追加：

```markdown
- 多工作树开发必须在实际 PR 工作树中执行全项目扫描；主工作区扫描结果不能替代 PR 工作树结果。
- PR 最终验收以 SonarCloud 按远端 base/head 生成的结果为准。
```

- [ ] **步骤 2：运行全部受影响测试**

```bash
mvn -q -Dtest=ChatServiceTests,DocumentParseServiceTests,FileEmbeddingServiceTests,DocForgeClientTests,MinioStorageServiceTests,ToolArgumentAuditSummarizerTests,ToolArgumentHasherTests,ToolExecutorTests,DocumentStorageServiceTests test
```

预期：65 个测试通过，0 failures、0 errors、0 skipped。

- [ ] **步骤 3：运行 JavaScript 验证、全量验证和差异检查**

```bash
sed -n '/<script type="module">/,/<\/script>/p' src/main/resources/static/eval.html \
  | sed '1d;$d' \
  | node --check --input-type=module
make verify
git diff --check bc69163
git diff --name-status bc69163 | rg '^D'
git diff bc69163 -- '*.java' '*.html' | rg '^\+.*(NOSONAR|SuppressWarnings)'
```

预期：

- JavaScript 语法检查退出码 0；
- `make verify` 运行 192 个测试，0 failures、0 errors、0 skipped，文档链接全部通过；
- `git diff --check` 无输出；
- 文件删除检查无输出；
- 新增抑制检查无输出。

最后两条 `rg` 在“无匹配”时以退出码 1 结束，这是预期结果，不代表验证失败。

- [ ] **步骤 4：提交规范更新**

```bash
git add docs/code-quality-guidelines.md
git commit -m "文档：补充日志安全编码规范"
```

---

### 任务 9：推送并以 SonarCloud 远端结果完成验收

**文件：** 无新增文件；本任务只推送当前分支并读取 PR #1 检查结果。

**接口：**

- 消费：任务 1—8 的全部提交。
- 产出：PR #1 Quality Gate 通过，开放问题数为 0，新代码 Security Rating 为 A。

- [ ] **步骤 1：确认工作树、提交序列和本地验证证据**

```bash
git status --short --branch
git log --oneline --decorate -12
make verify
git diff --check bc69163..HEAD
```

预期：工作树干净；最新提交均为中文；`make verify` 再次通过；差异检查无输出。

- [ ] **步骤 2：推送当前分支**

```bash
git push origin codex/minio-object-key-integrity
```

预期：远端分支更新到本地 HEAD。

- [ ] **步骤 3：等待 GitHub/SonarCloud 检查终态**

```bash
gh pr checks 1 --repo suwenfeng-coder/myllm --watch --interval 10
```

预期：`SonarCloud Code Analysis` 进入 `pass`。检查仍为 pending 时继续等待，不重复推送。

- [ ] **步骤 4：查询 SonarCloud 开放问题和 Quality Gate**

```bash
curl -sS 'https://sonarcloud.io/api/issues/search?componentKeys=suwenfeng-coder_myllm&pullRequest=1&statuses=OPEN&ps=100' \
  | jq '{total, issues: [.issues[] | {rule, component, line, message}]}'
curl -sS 'https://sonarcloud.io/api/qualitygates/project_status?projectKey=suwenfeng-coder_myllm&pullRequest=1' \
  | jq '{status: .projectStatus.status, conditions: .projectStatus.conditions}'
```

预期：第一条输出 `total: 0`；第二条输出 `status: "OK"`，安全评级条件满足 A。

- [ ] **步骤 5：仅当 DocumentParse 清洗仍未被污点分析识别时执行确定性回退**

如果剩余问题仅指向 `DocumentParseService` 的扩展名/失败原因日志，将两个日志改为完全不含
外部字符串：

```java
log.info("自动解析完成 requested=auto applied={} attempted={}",
        appliedMode, attempted);
```

```java
log.warn("自动解析候选失败，将尝试下一模式 mode={}", mode);
```

同时把两个新增测试的日志期望改为：

```java
assertEquals(
        "自动解析完成 requested=auto applied=local attempted=[local]",
        success);
```

```java
assertEquals(
        "自动解析候选失败，将尝试下一模式 mode=maker",
        warning);
assertEquals(
        "自动解析完成 requested=auto applied=docling attempted=[maker, docling]",
        success);
```

原始业务值断言保持不变，然后执行：

```bash
mvn -q -Dtest=DocumentParseServiceTests test
make verify
git add src/main/java/com/example/myllm/service/DocumentParseService.java \
  src/test/java/com/example/myllm/service/DocumentParseServiceTests.java
git commit -m "安全：移除解析日志中的外部字段"
git push origin codex/minio-object-key-integrity
gh pr checks 1 --repo suwenfeng-coder/myllm --watch --interval 10
```

预期：重新分析后开放问题为 0、Quality Gate 为 `OK`。如果剩余问题不属于该已定义回退，
停止修改并保留 Sonar API 的规则、文件、行号和消息，重新做根因分析，不使用抑制或降级门禁。
