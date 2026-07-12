# Harness 嵌套工具参数解析设计

## 背景

`HarnessActionParser` 当前只把 JSON 字符串、数字和布尔值转换为对应 Java 类型。数组和对象会进入
兜底分支并调用 `JsonNode.toString()`，因此结构化参数会退化为 JSON 字符串。

例如模型输出：

```json
{
  "action": "CALL_TOOL",
  "tool": "knowledge.search",
  "arguments": {
    "query": "制度依据",
    "fileIds": ["file-1", "file-2"]
  },
  "summary": "限定文件检索"
}
```

当前 `fileIds` 会被保存为字符串 `"[\"file-1\",\"file-2\"]"`，而
`KnowledgeSearchInput` 要求 `List<String>`。`ToolExecutor` 随后通过 Jackson 把参数 Map 转换为工具
声明类型时无法把该字符串绑定为 `List<String>`，合法的 `knowledge.search` 调用会以输入转换错误结束。

另一个相关问题是 `HarnessAction.CallTool` 使用 `Map.copyOf(arguments)` 创建参数副本。
`Map.copyOf` 不允许键或值为 `null`，因此包含合法 JSON 空值的参数会在构造动作时抛出
`NullPointerException`，绕过 `ParseResult` 的失败信封。

## 目标

1. 完整保留标准 JSON 参数的字符串、数字、布尔、空值、数组和对象结构。
2. `fileIds` 等数组参数能绑定到工具声明的集合字段。
3. 嵌套数组保持元素顺序，嵌套对象保持字段读取顺序。
4. 解析器产生的参数集合不可修改，并允许合法 JSON 空值。
5. 非标准节点或结构转换异常必须返回解析失败，不能把异常传播到 Harness 主循环。
6. 保持现有缺失、空值或非对象顶层 `arguments` 返回空 Map 的兼容行为。

## 非目标

- 不修改参数哈希和自动幂等键算法。
- 不修改 `ToolExecutor` 的输入归一化、超时或事务边界。
- 不修改工具输入 Record、Tool 接口、DDL、JPA 实体或数据库迁移脚本。
- 不增加新的工具参数 Schema 校验框架。
- 不修改模型动作 JSON 协议或工具清单。
- 不同时处理 Tool 结果脱敏、Prompt 持久化或其他审计问题。

## 方案比较

### 方案一：显式递归转换

`HarnessActionParser` 根据 `JsonNode` 类型递归构建 Java 值。每一种标准 JSON 类型都有明确映射，
领域对象中不会出现 Jackson 节点类型。

优点：行为清晰、类型边界稳定、便于针对空值和不可修改性测试。缺点：需要增加少量递归辅助方法。

### 方案二：ObjectMapper 通用转换

通过 `ObjectMapper.convertValue` 把参数树整体转换为 `Map<String, Object>`。

优点：代码较短。缺点：嵌套集合具体实现、数字类型和异常边界更多依赖 Jackson 默认行为，解析规则不如
显式转换直观。

### 方案三：保留 JsonNode

直接把 `ArrayNode`、`ObjectNode` 等值放入参数 Map。

优点：代码改动最少。缺点：领域层开始依赖 Jackson，调用方必须理解节点类型，也会让后续参数哈希和
工具绑定承担额外分支。

本阶段采用方案一。

## 类型映射

`jsonValue(JsonNode node)` 按以下固定规则转换：

| JSON 类型 | Java 类型 | 规则 |
|-----------|-----------|------|
| 字符串 | `String` | 使用 `textValue()` |
| 数字 | `Number` | 使用 `numberValue()`，不转成字符串 |
| 布尔 | `Boolean` | 使用 `booleanValue()` |
| 空值 | `null` | 保留 Java `null` |
| 数组 | `List<Object>` | 按原顺序递归转换并包装为不可修改 List |
| 对象 | `Map<String, Object>` | 按字段读取顺序写入 `LinkedHashMap`，递归转换后包装为不可修改 Map |

所有从模型文本解析出的标准 JSON 节点都被上述规则覆盖。若出现未覆盖的非标准节点，解析器抛出不包含
原始参数内容的固定结构异常，并由顶层 `parse` 转换为失败 `ParseResult`。

## 组件修改

### HarnessActionParser

保留 `readArguments` 作为顶层参数入口，并将嵌套转换拆为三个小方法：

```java
private static Map<String, Object> readArguments(JsonNode node)
private static Object jsonValue(JsonNode node)
private static Map<String, Object> jsonObject(JsonNode node)
private static List<Object> jsonArray(JsonNode node)
```

`readArguments` 的兼容规则保持不变：

- `arguments` 缺失或为 JSON 空值时返回 `Map.of()`。
- `arguments` 不是对象时仍返回 `Map.of()`。
- 对象参数逐字段调用 `jsonValue`，不再把容器节点转换为字符串。

顶层 `parse` 在现有 JSON 语法异常之外，接住结构转换产生的 `IllegalArgumentException`，返回固定的
“工具参数结构解析失败”消息。失败消息不得拼接原始参数值。

### HarnessAction.CallTool

参数防御性复制改为：

```java
arguments = arguments == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
```

这样仍然阻止调用方修改顶层参数 Map，同时保留插入顺序并允许值为 `null`。解析器生成的嵌套 Map 和
List 自身也会包装为不可修改集合。

手工构造 `CallTool` 时，Record 只对顶层 Map 做防御性复制，与现有浅复制语义一致；本阶段不增加通用
深复制工具。

## 数据流

```text
模型输出 CALL_TOOL JSON
  -> ObjectMapper 解析为 JsonNode
  -> HarnessActionParser 递归转换 arguments
  -> HarnessAction.CallTool 保存不可修改结构
  -> HarnessOrchestrator 将参数传给 ToolExecutor
  -> ToolExecutor 按工具声明类型转换
  -> KnowledgeSearchInput.fileIds 获得 List<String>
```

解析修复不会改变参数值、字段名或数组顺序，只修复运行时结构类型。

## 错误与兼容边界

- 标准 JSON 的数组、对象和空值不再触发字符串退化或空值复制异常。
- 非对象顶层 `arguments` 继续视为空参数，避免扩大本次行为变化。
- 参数结构转换失败时不执行工具，模型动作按现有解析失败流程处理。
- 不记录原始参数到日志或错误消息。
- 不变更已保存的历史 Tool 调用记录。
- 参数哈希仍使用当前算法，规范化哈希在下一项独立优化中处理。

## 测试设计

### HarnessActionParserTests

1. 解析 `knowledge.search` 动作后，`fileIds` 是 `List<String>`，不是 JSON 字符串。
2. 将解析后的参数通过 `ObjectMapper.convertValue` 转成 `KnowledgeSearchInput`，查询和文件列表均正确。
3. 多层对象和数组递归保留 Map、List、数字、布尔和字符串类型。
4. 顶层字段、嵌套对象字段和数组元素中的 JSON 空值均保留为 Java `null`。
5. 解析器返回的顶层 Map、嵌套 Map 和嵌套 List 均不可修改。
6. 缺失、空值或非对象顶层 `arguments` 继续返回空 Map。
7. 现有字符串参数、FINAL 动作、Markdown JSON、未知动作和非法 JSON 测试继续通过。

### 完整验证

定向运行 `HarnessActionParserTests` 后执行 `make verify` 与 `git diff --check`。

## 验收标准

- 合法 `fileIds` 数组能绑定为 `KnowledgeSearchInput.fileIds`。
- 数组和对象不再以 JSON 字符串出现在 `CallTool.arguments`。
- JSON 空值不会导致动作构造异常。
- 参数集合保持顺序且不可修改。
- 不改变参数哈希、幂等、工具执行、数据库或审计语义。
- 定向测试与完整仓库验证通过。
