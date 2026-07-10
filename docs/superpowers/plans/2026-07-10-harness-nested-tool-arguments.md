# Harness 嵌套工具参数解析实施计划

> **供执行代理使用：** 必须按任务顺序使用 `executing-plans` 与 `test-driven-development` 技能实施；每个生产代码变更都要先看到对应测试按预期失败。

**目标：** 让 Harness 模型动作中的数组、对象和空值保持结构化 Java 类型，并能正确绑定到工具声明输入。

**架构：** `HarnessActionParser` 显式递归转换标准 JSON 节点，容器结果保持顺序并包装为不可修改集合；`HarnessAction.CallTool` 使用允许空值的保序防御性副本。现有 ToolExecutor、参数哈希、幂等键和数据库结构保持不变。

**技术栈：** Java 17、Jackson、JUnit 5、Spring Boot、Maven。

## 全局约束

- JSON 字符串、数字、布尔、空值、数组和对象必须映射为对应 Java 结构，禁止把数组或对象转换为 JSON 字符串。
- 数组保持元素顺序，对象使用 `LinkedHashMap` 保持字段读取顺序。
- 解析器产生的顶层 Map、嵌套 Map 和嵌套 List 均不可修改，并允许值为 Java `null`。
- 缺失、空值或非对象顶层 `arguments` 继续返回空 Map。
- 非标准节点返回固定解析失败消息，错误内容不得包含原始参数。
- 不修改参数哈希、自动幂等键、ToolExecutor、工具输入 Record、DDL、实体或迁移脚本。
- 新增文档、注释和提交说明全部使用中文。

---

### 任务 1：递归解析并冻结嵌套工具参数

**文件：**

- 修改：`src/main/java/com/example/myllm/harness/domain/HarnessActionParser.java`
- 修改：`src/main/java/com/example/myllm/harness/domain/HarnessAction.java`
- 修改：`src/test/java/com/example/myllm/harness/domain/HarnessActionParserTests.java`

**接口：**

- 保持：`HarnessActionParser.ParseResult parse(String raw)`。
- 保持：`HarnessAction.CallTool(String tool, Map<String, Object> arguments, String summary)`。
- 新增内部方法：`jsonObject(JsonNode)` 与 `jsonArray(JsonNode)`，不增加公开 API。

- [x] **步骤 1：先写嵌套结构、空值和不可修改性测试**

在测试类中保存同一个 `ObjectMapper`，用于解析器初始化和工具输入绑定：

```java
private ObjectMapper objectMapper;
private HarnessActionParser parser;

@BeforeEach
void setUp() {
    objectMapper = new ObjectMapper();
    parser = new HarnessActionParser(objectMapper);
}
```

新增数组绑定测试：

```java
@Test
void parsesFileIdsAsListAndBindsKnowledgeSearchInput() {
    String json = """
            {"action":"CALL_TOOL","tool":"knowledge.search","arguments":{
              "query":"制度依据","fileIds":["file-1","file-2"]
            },"summary":"限定文件检索"}
            """;

    HarnessActionParser.ParseResult result = assertDoesNotThrow(() -> parser.parse(json));

    assertTrue(result.success());
    HarnessAction.CallTool call = assertInstanceOf(HarnessAction.CallTool.class, result.action());
    List<?> fileIds = assertInstanceOf(List.class, call.arguments().get("fileIds"));
    assertEquals(List.of("file-1", "file-2"), fileIds);
    KnowledgeSearchInput input = objectMapper.convertValue(call.arguments(), KnowledgeSearchInput.class);
    assertEquals("制度依据", input.query());
    assertEquals(List.of("file-1", "file-2"), input.fileIds());
}
```

新增递归结构、空值、顺序和不可修改性测试：

```java
@Test
void preservesNestedValuesAndReturnsUnmodifiableCollections() {
    String json = """
            {"action":"CALL_TOOL","tool":"knowledge.search","arguments":{
              "optional":null,
                  "filters":{
                    "enabled":true,
                    "threshold":1.5,
                    "optionalNested":null,
                    "tags":["A",null,{"rank":2}]
                  }
            },"summary":"测试嵌套参数"}
            """;

    HarnessActionParser.ParseResult result = parser.parse(json);

    assertTrue(result.success());
    HarnessAction.CallTool call = assertInstanceOf(HarnessAction.CallTool.class, result.action());
    assertEquals(List.of("optional", "filters"), List.copyOf(call.arguments().keySet()));
    assertTrue(call.arguments().containsKey("optional"));
    assertNull(call.arguments().get("optional"));

    Map<?, ?> filters = assertInstanceOf(Map.class, call.arguments().get("filters"));
    assertEquals(List.of("enabled", "threshold", "optionalNested", "tags"), List.copyOf(filters.keySet()));
    assertEquals(Boolean.TRUE, filters.get("enabled"));
    assertEquals(1.5, filters.get("threshold"));
    assertTrue(filters.containsKey("optionalNested"));
    assertNull(filters.get("optionalNested"));
    List<?> tags = assertInstanceOf(List.class, filters.get("tags"));
    assertEquals("A", tags.get(0));
    assertNull(tags.get(1));
    Map<?, ?> ranked = assertInstanceOf(Map.class, tags.get(2));
    assertEquals(2, ranked.get("rank"));

    assertThrows(UnsupportedOperationException.class, call.arguments()::clear);
    assertThrows(UnsupportedOperationException.class, filters::clear);
    assertThrows(UnsupportedOperationException.class, tags::clear);
    assertThrows(UnsupportedOperationException.class, ranked::clear);
}
```

新增顶层兼容测试：

```java
@Test
void keepsMissingNullAndNonObjectArgumentsEmpty() {
    List<String> actions = List.of(
            "{\"action\":\"CALL_TOOL\",\"tool\":\"file.list\"}",
            "{\"action\":\"CALL_TOOL\",\"tool\":\"file.list\",\"arguments\":null}",
            "{\"action\":\"CALL_TOOL\",\"tool\":\"file.list\",\"arguments\":[]}");

    for (String action : actions) {
        HarnessActionParser.ParseResult result = parser.parse(action);
        assertTrue(result.success());
        HarnessAction.CallTool call = assertInstanceOf(HarnessAction.CallTool.class, result.action());
        assertTrue(call.arguments().isEmpty());
    }
}
```

新增非标准节点安全失败测试：通过测试专用 `ObjectMapper` 构造包含 `POJONode` 的参数树，断言解析结果失败，
错误消息固定为 `工具参数结构解析失败`，且不包含原始对象内容。

新增 `CallTool` 顶层防御性复制测试：使用允许空值的可变 `LinkedHashMap` 构造动作，随后修改源 Map，断言动作中的
字段顺序、原值和空值保持不变，新增源字段不可见，且动作参数 Map 不可修改。

需要增加以下导入：

```java
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.myllm.harness.adapter.tool.model.KnowledgeSearchInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```

- [x] **步骤 2：运行测试并确认按预期失败**

运行：

```bash
mvn -q -Dtest=HarnessActionParserTests test
```

预期：`fileIds` 实际为字符串而导致类型断言失败；包含 `optional:null` 的动作在 `Map.copyOf` 处抛出空值异常。现有标量、FINAL、Markdown、未知动作和非法 JSON 测试继续通过。

- [x] **步骤 3：实现递归 JSON 类型转换**

在 `HarnessActionParser` 中增加 `Collections` 导入，并将参数转换实现替换为：

```java
private static Map<String, Object> readArguments(JsonNode node) {
    if (node == null || node.isNull() || !node.isObject()) {
        return Map.of();
    }
    return jsonObject(node);
}

private static Object jsonValue(JsonNode node) {
    if (node == null || node.isNull()) {
        return null;
    }
    if (node.isTextual()) {
        return node.textValue();
    }
    if (node.isNumber()) {
        return node.numberValue();
    }
    if (node.isBoolean()) {
        return node.booleanValue();
    }
    if (node.isArray()) {
        return jsonArray(node);
    }
    if (node.isObject()) {
        return jsonObject(node);
    }
    throw new IllegalArgumentException("不支持的工具参数节点类型");
}

private static Map<String, Object> jsonObject(JsonNode node) {
    Map<String, Object> values = new LinkedHashMap<>();
    node.fields().forEachRemaining(entry -> values.put(entry.getKey(), jsonValue(entry.getValue())));
    return Collections.unmodifiableMap(values);
}

private static List<Object> jsonArray(JsonNode node) {
    List<Object> values = new ArrayList<>();
    node.forEach(item -> values.add(jsonValue(item)));
    return Collections.unmodifiableList(values);
}
```

在 `parse` 的 JSON 异常分支后增加固定结构失败信封：

```java
} catch (IllegalArgumentException exception) {
    return ParseResult.failure("工具参数结构解析失败");
}
```

不得把 `exception.getMessage()` 或原始参数拼入失败消息。

- [x] **步骤 4：实现允许空值的 CallTool 防御性副本**

在 `HarnessAction` 增加 `Collections` 与 `LinkedHashMap` 导入，并替换 Record 构造逻辑：

```java
public CallTool {
    arguments = arguments == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    summary = summary == null ? "" : summary;
}
```

不得改动 `Final`、`Citation` 或动作协议。

- [x] **步骤 5：运行定向测试并确认通过**

运行：

```bash
mvn -q -Dtest=HarnessActionParserTests test
```

预期：新增与现有测试全部通过，数组可绑定为 `KnowledgeSearchInput.fileIds`，所有解析器容器不可修改。

- [x] **步骤 6：提交任务 1（与完整验证结果合并为最终提交）**

```bash
git add src/main/java/com/example/myllm/harness/domain/HarnessActionParser.java \
  src/main/java/com/example/myllm/harness/domain/HarnessAction.java \
  src/test/java/com/example/myllm/harness/domain/HarnessActionParserTests.java
git commit -m "修复：保留 Harness 嵌套工具参数结构"
```

---

### 任务 2：完整验证与范围审查

**文件：**

- 复核：`src/main/java/com/example/myllm/harness/domain/HarnessActionParser.java`
- 复核：`src/main/java/com/example/myllm/harness/domain/HarnessAction.java`
- 复核：`src/test/java/com/example/myllm/harness/domain/HarnessActionParserTests.java`
- 复核：`docs/superpowers/plans/2026-07-10-harness-nested-tool-arguments.md`

- [x] **步骤 1：执行完整仓库验证**

```bash
make verify
```

预期：全部测试通过，文档链接检查通过。

- [x] **步骤 2：执行补充静态检查**

```bash
git diff --check
rg -n "return node\.toString\(\)" \
  src/main/java/com/example/myllm/harness/domain/HarnessActionParser.java
```

预期：`git diff --check` 无输出；旧容器字符串兜底无匹配。

- [x] **步骤 3：确认最终变更范围**

```bash
git status --short
git diff --stat
```

预期：只包含 Parser、Action、对应测试和本计划；不存在 ToolExecutor、参数哈希、DDL、实体、迁移脚本或文件删除。
