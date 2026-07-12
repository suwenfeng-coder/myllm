# Harness 工具参数结构化审计实施计划

> **供执行代理使用：** 必须按任务顺序使用 `executing-plans` 与 `test-driven-development` 技能实施；每个生产代码变更都要先看到对应测试按预期失败。

**目标：** 将 `ToolExecutor` 的工具参数审计改为版本化、合法且不包含实际参数值的结构摘要 JSON。

**架构：** 在 `harness.application` 中新增单一职责的 `ToolArgumentAuditSummarizer`，通过 Jackson 将声明输入类型和运行时输入归一化，再生成受深度、字段数、元素类型数和字节数限制的结构摘要。`ToolExecutor` 只负责调用该组件并持久化结果，现有参数哈希、幂等键、事务和工具执行语义保持不变。

**技术栈：** Java 17、Spring Boot、Jackson、JUnit 5、Spring Data JPA、H2 测试数据库。

## 全局约束

- 审计级别固定为程度 1：保留结构和规模，不保存字符串、数字、布尔、枚举、文件标识、任务标识或查询原文等实际值。
- `schemaVersion` 固定为 `1`；非空结果必须是合法 JSON。
- 最大递归深度为 `4`，单对象最多记录 `32` 个字段，集合最多记录 `8` 种元素类型，最终 UTF-8 JSON 最大为 `4096` 字节。
- 达到深度或字段限制时写入 `"truncated":true`；最终结果超限时返回固定安全降级 JSON，禁止截断 JSON 字符串。
- 声明类型为 `Void` 或输入为 `null` 时返回 Java `null`，数据库保存 `NULL`。
- 声明为 `Map` 的动态输入只保存 `fieldCount`，不得保存动态键名。
- 摘要异常必须安全降级，不能阻断工具执行，也不能调用输入对象的 `toString()`。
- 不修改参数哈希、幂等键、事务边界、DDL、JPA 实体或历史数据。
- 新增文档、代码注释和提交说明全部使用中文。

---

### 任务 1：建立结构摘要组件的基础行为

**文件：**

- 新建：`src/main/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizer.java`
- 新建：`src/test/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizerTests.java`

**接口：**

- 输入：工具声明的 `Class<?> declaredInputType` 与原始 `Object input`。
- 输出：`String summarize(Class<?> declaredInputType, Object input)`，返回合法 JSON 或 Java `null`。
- 后续依赖：任务 3 的 `ToolExecutor` 将直接注入并调用此组件。

- [x] **步骤 1：先写基础行为测试**

测试使用 `new ObjectMapper().findAndRegisterModules()` 创建真实 Jackson 实例，并至少覆盖以下断言：

```java
@Test
void summarizesConcreteInputWithoutPersistingValues() throws Exception {
    String query = "机密查询原文";
    String fileId = "file-secret-001";

    String json = summarizer.summarize(
            KnowledgeSearchInput.class,
            Map.of("query", query, "fileIds", List.of(fileId, "file-secret-002")));

    JsonNode root = objectMapper.readTree(json);
    assertEquals(1, root.path("schemaVersion").asInt());
    assertEquals("KnowledgeSearchInput", root.path("inputType").asText());
    assertEquals("object", root.at("/summary/type").asText());
    assertEquals(query.length(), root.at("/summary/fields/query/length").asInt());
    assertEquals(2, root.at("/summary/fields/fileIds/count").asInt());
    assertEquals("string", root.at("/summary/fields/fileIds/elementTypes/0").asText());
    assertFalse(json.contains(query));
    assertFalse(json.contains(fileId));
}

@Test
void omitsDynamicMapKeysAndValues() throws Exception {
    String json = summarizer.summarize(
            Map.class,
            Map.of("用户输入的机密键", "机密值", "另一个键", 99));

    JsonNode root = objectMapper.readTree(json);
    assertEquals("object", root.at("/summary/type").asText());
    assertEquals(2, root.at("/summary/fieldCount").asInt());
    assertFalse(json.contains("用户输入的机密键"));
    assertFalse(json.contains("机密值"));
    assertFalse(json.contains("99"));
}

@Test
void returnsNullForAbsentInput() {
    assertNull(summarizer.summarize(Void.class, null));
    assertNull(summarizer.summarize(String.class, null));
}
```

同一测试类补充一个包含字符串、数字、布尔、枚举、集合、Record 和普通 Java 对象的组合输入，验证：

```java
assertEquals("string", root.at("/summary/fields/text/type").asText());
assertEquals("number", root.at("/summary/fields/number/type").asText());
assertEquals("boolean", root.at("/summary/fields/enabled/type").asText());
assertEquals("string", root.at("/summary/fields/status/type").asText());
assertEquals("array", root.at("/summary/fields/items/type").asText());
assertEquals("object", root.at("/summary/fields/details/type").asText());
```

- [x] **步骤 2：运行测试并确认按预期失败**

运行：

```bash
mvn -q -Dtest=ToolArgumentAuditSummarizerTests test
```

预期：测试编译失败，明确提示 `ToolArgumentAuditSummarizer` 尚不存在；失败原因不能是测试语法或测试数据错误。

- [x] **步骤 3：实现最小可用的摘要组件**

组件采用以下结构：

```java
@Component
public class ToolArgumentAuditSummarizer {

    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public ToolArgumentAuditSummarizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String summarize(Class<?> declaredInputType, Object input) {
        if (input == null || Void.class.equals(declaredInputType) || void.class.equals(declaredInputType)) {
            return null;
        }
        try {
            Object normalized = normalizeInput(declaredInputType, input);
            JsonNode valueTree = objectMapper.valueToTree(normalized);
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("schemaVersion", SCHEMA_VERSION);
            envelope.put("inputType", declaredInputType.getSimpleName());
            envelope.set("summary", summarizeNode(objectMapper.constructType(declaredInputType), valueTree, 0));
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工具参数摘要无法序列化", exception);
        }
    }
}
```

内部方法必须按以下职责拆分，避免把类型分支全部塞入 `summarize`：

```java
private Object normalizeInput(Class<?> declaredInputType, Object input)
private ObjectNode summarizeNode(JavaType declaredType, JsonNode value, int depth)
private ObjectNode summarizeObject(JavaType declaredType, JsonNode value, int depth)
private ObjectNode summarizeArray(JsonNode value)
private static String nodeType(JsonNode value)
private static boolean isDynamicObjectType(JavaType declaredType)
```

基础实现规则：

- `Map` 转具体声明类型使用 `objectMapper.convertValue(input, declaredInputType)`。
- 文本类型统一复用 `TYPE_STRING`，摘要 JSON 仍只写 `type=string` 和 `length`。
- 数字、布尔节点只写类型。
- 数组只写 `count` 及去重、排序后的 `elementTypes`。
- 声明类型为 `Map` 或 `Object` 的对象节点只写 `fieldCount`。
- 具体对象通过 Jackson `BeanDescription.findProperties()` 获取固定序列化属性，按属性名排序；递归时使用 `BeanPropertyDefinition.getPrimaryType()`。
- 嵌套空值写为 `{"type":"null"}`。

- [x] **步骤 4：运行基础测试并确认通过**

运行：

```bash
mvn -q -Dtest=ToolArgumentAuditSummarizerTests test
```

预期：`ToolArgumentAuditSummarizerTests` 全部通过；每个非空结果都能被 `ObjectMapper.readTree` 重新解析。

- [ ] **步骤 5：提交任务 1（Git 写入受限，合并到最终手动提交）**

```bash
git add src/main/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizer.java \
  src/test/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizerTests.java
git commit -m "功能：新增工具参数结构摘要组件"
```

---

### 任务 2：补齐资源限制与安全降级

**文件：**

- 修改：`src/main/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizer.java`
- 修改：`src/test/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizerTests.java`

**接口：**

- 保持 `summarize(Class<?>, Object)` 不变。
- 输出新增机械限制和固定安全降级语义。

- [x] **步骤 1：先写限制与降级测试**

新增测试并验证以下行为：

```java
@Test
void marksNestedObjectWhenDepthLimitIsReached() throws Exception {
    NestedInput input = nestedInput(6);

    JsonNode root = objectMapper.readTree(summarizer.summarize(NestedInput.class, input));

    assertTrue(root.at("/summary/fields/child/fields/child/fields/child/fields/child/truncated").asBoolean());
}

@Test
void capsObjectFieldsAtThirtyTwo() throws Exception {
    JsonNode root = objectMapper.readTree(summarizer.summarize(ManyFieldsInput.class, new ManyFieldsInput()));

    assertEquals(32, root.at("/summary/fields").size());
    assertTrue(root.at("/summary/truncated").asBoolean());
}

@Test
void returnsSafeFallbackWhenConcreteMapConversionFails() throws Exception {
    String json = summarizer.summarize(NumberInput.class, Map.of("number", "不是数字"));

    assertEquals(
            objectMapper.readTree("{\"schemaVersion\":1,\"summary\":{\"type\":\"unavailable\"}}"),
            objectMapper.readTree(json));
}
```

再使用一个覆盖 `ObjectMapper.writeValueAsBytes` 的测试实例强制返回 `4097` 字节，断言摘要返回同一个固定安全降级 JSON，而不是被截断的非法 JSON。另加一个 getter 主动抛异常的普通对象，断言 `summarize` 不抛异常且返回安全降级 JSON。

- [x] **步骤 2：运行测试并确认按预期失败**

运行：

```bash
mvn -q -Dtest=ToolArgumentAuditSummarizerTests test
```

预期：新增的深度、字段数、输出字节数或异常降级断言失败；原有基础行为测试继续通过。

- [x] **步骤 3：实现固定限制和安全降级**

增加以下常量：

```java
private static final int MAX_DEPTH = 4;
private static final int MAX_FIELDS = 32;
private static final int MAX_ELEMENT_TYPES = 8;
private static final int MAX_JSON_BYTES = 4096;
private static final String TYPE_STRING = "string";
private static final String SAFE_FALLBACK_JSON =
        "{\"schemaVersion\":1,\"summary\":{\"type\":\"unavailable\"}}";
```

将公开方法收口为不可抛出摘要异常的边界：

```java
public String summarize(Class<?> declaredInputType, Object input) {
    if (input == null || Void.class.equals(declaredInputType) || void.class.equals(declaredInputType)) {
        return null;
    }
    try {
        byte[] json = buildSummary(declaredInputType, input);
        return json.length <= MAX_JSON_BYTES
                ? new String(json, StandardCharsets.UTF_8)
                : SAFE_FALLBACK_JSON;
    } catch (Exception exception) {
        return SAFE_FALLBACK_JSON;
    }
}
```

`buildSummary` 负责归一化、生成节点并调用 `objectMapper.writeValueAsBytes`。对象递归达到 `depth >= MAX_DEPTH` 时只返回对象类型及 `truncated=true`；属性列表超过 `MAX_FIELDS` 时只取排序后的前 32 项，并在当前对象摘要写入 `truncated=true`；元素类型使用 `TreeSet` 排序并通过 `limit(MAX_ELEMENT_TYPES)` 写入。禁止使用 `substring` 或其他方式截取最终 JSON。

- [x] **步骤 4：运行摘要组件测试并确认通过**

运行：

```bash
mvn -q -Dtest=ToolArgumentAuditSummarizerTests test
```

预期：基础类型、动态 Map、深度、字段数、输出大小、异常降级与 JSON 可解析性测试全部通过。

- [ ] **步骤 5：提交任务 2（Git 写入受限，合并到最终手动提交）**

```bash
git add src/main/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizer.java \
  src/test/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizerTests.java
git commit -m "安全：限制工具参数审计摘要资源用量"
```

---

### 任务 3：接入 ToolExecutor 并验证持久化

**文件：**

- 修改：`src/main/java/com/example/myllm/harness/application/ToolExecutor.java`
- 修改：`src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java`
- 修改：`src/test/java/com/example/myllm/harness/application/HarnessOrchestratorReplayTests.java`

**接口：**

- 消费：`ToolArgumentAuditSummarizer.summarize(Class<?>, Object)`。
- 保持：`ToolExecutor.execute`、参数哈希、幂等键、状态迁移和结果处理接口不变。

- [x] **步骤 1：先写持久化回归测试**

将 `ToolArgumentAuditSummarizer.class` 加入 `@Import`，注入测试用 `ObjectMapper`，新增以下测试：

```java
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
            run.getRunId(), null, "idem-audit", Set.of(TestConfig.ECHO_TEST));

    ToolResult<?> result = toolExecutor.execute(context, TestConfig.ECHO_TEST, secret);

    assertTrue(result.success());
    String auditJson = toolCallRepository.findAll().get(0).getArgumentsRedactedJson();
    JsonNode audit = objectMapper.readTree(auditJson);
    assertEquals(1, audit.path("schemaVersion").asInt());
    assertEquals("String", audit.path("inputType").asText());
    assertEquals("string", audit.at("/summary/type").asText());
    assertEquals(secret.length(), audit.at("/summary/length").asInt());
    assertFalse(auditJson.contains(secret));
}
```

新增无参测试工具并断言有 `runId` 的 `Void` 工具调用将 `argumentsRedactedJson` 保存为 `null`。新增一个声明输入 getter 会抛异常、但工具本身可正常成功的测试工具，断言工具执行成功且持久化内容等于固定安全降级 JSON，从集成层证明摘要异常不会阻断调用。

- [x] **步骤 2：运行 ToolExecutor 测试并确认按预期失败**

运行：

```bash
mvn -q -Dtest=ToolExecutorTests test
```

预期：现有 `redactArguments` 仍会保存参数原文或 Java 字符串表示，导致合法 JSON、隐私或 `NULL` 断言失败。

- [x] **步骤 3：注入并调用结构摘要组件**

在 `ToolExecutor` 构造器中新增依赖：

```java
private final ToolArgumentAuditSummarizer argumentAuditSummarizer;

public ToolExecutor(
        ToolRegistry toolRegistry,
        ToolPolicyEngine policyEngine,
        HarnessToolCallRepository toolCallRepository,
        HarnessProperties properties,
        ObjectMapper objectMapper,
        ToolArgumentAuditSummarizer argumentAuditSummarizer) {
    this.toolRegistry = toolRegistry;
    this.policyEngine = policyEngine;
    this.toolCallRepository = toolCallRepository;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.argumentAuditSummarizer = argumentAuditSummarizer;
}
```

创建审计记录时传入声明输入类型：

```java
audit = createAuditRecord(
        safeContext,
        descriptor,
        tool.inputType(),
        idempotencyKey,
        input);
```

并在 `createAuditRecord` 中替换原实现：

```java
auditRecord.setArgumentsRedactedJson(argumentAuditSummarizer.summarize(declaredInputType, input));
```

删除仅用于旧审计逻辑的 `redactArguments`；保留 `truncate`，因为结果预览和错误消息仍使用它。不得修改 `hashInput` 或 `resolveIdempotencyKey`。

所有通过显式 `@Import` 装配 `ToolExecutor` 的 Spring 切片测试都必须同时导入
`ToolArgumentAuditSummarizer.class`；当前包括 `ToolExecutorTests` 与 `HarnessOrchestratorReplayTests`。

- [x] **步骤 4：运行定向回归测试**

运行：

```bash
mvn -q -Dtest=ToolArgumentAuditSummarizerTests,ToolExecutorTests,HarnessOrchestratorReplayTests test
```

预期：摘要组件和 ToolExecutor 测试全部通过；审计字段可解析、不含秘密值，无参调用保存 `NULL`，降级时工具仍执行成功。

- [ ] **步骤 5：提交任务 3（Git 写入受限，合并到最终手动提交）**

```bash
git add src/main/java/com/example/myllm/harness/application/ToolExecutor.java \
  src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java \
  src/test/java/com/example/myllm/harness/application/HarnessOrchestratorReplayTests.java
git commit -m "修复：持久化工具参数结构化审计"
```

---

### 任务 4：执行完整验证与变更审查

**文件：**

- 复核：`src/main/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizer.java`
- 复核：`src/main/java/com/example/myllm/harness/application/ToolExecutor.java`
- 复核：`src/test/java/com/example/myllm/harness/application/ToolArgumentAuditSummarizerTests.java`
- 复核：`src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java`
- 复核：`src/test/java/com/example/myllm/harness/application/HarnessOrchestratorReplayTests.java`

- [x] **步骤 1：执行完整仓库验证**

```bash
make verify
```

预期：全部单元测试通过，文档链接检查通过，无失败、错误或跳过导致的风险提示。

- [x] **步骤 2：执行补充静态检查**

```bash
git diff --check
rg -n "redactArguments|setArgumentsRedactedJson\(truncate" \
  src/main/java/com/example/myllm/harness/application/ToolExecutor.java
```

预期：`git diff --check` 无输出；第二条命令无匹配。`hashInput` 中用于生成哈希的
`String.valueOf(input)` 属于明确非目标，必须保持不变；随后人工核对差异中没有 DDL、实体、参数哈希或幂等键变更。

- [x] **步骤 3：确认最终工作树范围**

```bash
git status --short
git diff --stat
```

预期：只包含本计划列出的组件、测试、回放测试装配修复和计划文档；不存在无关文件或批量删除。
