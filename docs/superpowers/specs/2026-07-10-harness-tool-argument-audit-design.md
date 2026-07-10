# Harness 工具参数结构化审计设计

## 背景

`ToolExecutor` 当前通过 `String.valueOf(input)` 生成所谓的脱敏参数，并将结果截断后写入
`harness_tool_call.arguments_redacted_json`。该实现有两个问题：

1. Java 对象的字符串表示可能包含查询内容、文件标识、任务标识或其他敏感值，并未真正脱敏。
2. `{query=xxx}` 等 Java 字符串表示不是合法 JSON，而正式 MySQL DDL 将该列定义为 `JSON`，
   真实工具调用可能在持久化审计记录时失败。

ADR-001 明确要求默认不持久化完整 Tool 参数，只保存哈希、长度和脱敏摘要。本设计采用“程度 1”
审计：只保存参数结构与规模，不保存任何实际参数值。

## 目标

1. `arguments_redacted_json` 写入值始终为合法 JSON 或数据库 `NULL`。
2. 摘要可展示输入类型、字段名、数据类型、字符串长度、集合数量和元素类型。
3. 摘要中不得出现参数本身的字符串值、数值、布尔值、枚举值、文件标识、任务标识或查询原文；
   字符串长度、集合数量和字段数量属于允许保存的结构元数据。
4. 摘要生成失败时采用固定的安全 JSON，并且不得阻断正常工具执行。
5. 对摘要深度、字段数和输出大小实施机械限制，避免异常输入放大审计开销。

## 非目标

- 不修改参数哈希和幂等键算法。
- 不拆分 `ToolExecutor` 当前事务。
- 不实现 Harness 审批参数展示或字段白名单。
- 不修改历史审计记录。
- 不修改 MySQL DDL、JPA 实体字段类型或数据库迁移脚本。
- 不处理 Prompt、Tool 结果、`transaction_log` 等其他敏感数据持久化问题。

## 组件设计

新增 `ToolArgumentAuditSummarizer`，放在 `harness.application` 包中。该组件只负责把工具输入
转换为结构摘要，不执行工具、不写数据库，也不参与策略判断。

公开接口为：

```java
String summarize(Class<?> declaredInputType, Object input)
```

`ToolExecutor` 在创建 `HarnessToolCall` 时，将 `tool.inputType()` 与原始输入传给该组件，
并把返回值写入 `argumentsRedactedJson`。现有 `argumentsHash`、幂等键、Tool 调用状态和结果审计
流程保持不变。

## 摘要格式

摘要固定使用版本化 JSON 包装：

```json
{
  "schemaVersion": 1,
  "inputType": "KnowledgeSearchInput",
  "summary": {
    "type": "object",
    "fields": {
      "fileIds": {
        "type": "array",
        "count": 2,
        "elementTypes": ["string"]
      },
      "query": {
        "type": "string",
        "length": 12
      }
    }
  }
}
```

字段名按字典顺序输出，使测试和运维展示稳定。`schemaVersion` 用于未来演进摘要结构，初始值固定为
`1`。

## 类型摘要规则

### 空输入

工具声明为 `Void` 或输入为 `null` 时返回 Java `null`，数据库保存 `NULL`。

### 字符串及文本节点

只保存：

```json
{"type":"string","length":12}
```

不保存文本内容。日期、枚举等最终序列化为文本的值同样只记录为字符串长度，不保留实际值。

### 数字与布尔值

分别只保存：

```json
{"type":"number"}
```

```json
{"type":"boolean"}
```

不得保存数值或真假值。

### 数组与集合

保存集合数量和去重、排序后的元素类型，最多保留 8 种元素类型：

```json
{
  "type": "array",
  "count": 2,
  "elementTypes": ["string"]
}
```

不保存集合元素，也不保存各元素的长度。

### Record 与普通对象

保存声明类型的固定字段名，并递归生成各字段的结构摘要。若调用方传入 `Map`，但工具声明了具体
输入类型，则先通过现有 Jackson 配置转换为声明类型，再生成摘要。转换失败时直接进入安全降级，
不得使用原始 Map 的动态键名。

### 动态 Map

当工具声明的输入类型本身就是 `Map` 时，不保存任何 Map 键名，因为键名也可能由攻击者控制并
包含敏感数据。只保存：

```json
{"type":"object","fieldCount":3}
```

## 资源限制

结构摘要采用以下固定限制：

- 最大递归深度：4。
- 单个对象最多记录字段：32。
- 集合最多记录元素类型：8。
- 最终 UTF-8 JSON 最大长度：4096 字节。

达到深度或字段限制时，在对应节点增加 `"truncated":true`。不得在 JSON 序列化完成后直接截取
字符串；若最终结果仍超过 4096 字节，改用固定安全降级 JSON。

## 安全降级

任何类型转换、反射读取、Jackson 树转换或 JSON 序列化异常都不得向上传播，也不得调用输入对象
的 `toString()`。统一返回以下常量：

```json
{
  "schemaVersion": 1,
  "summary": {
    "type": "unavailable"
  }
}
```

该常量本身必须通过单元测试验证为合法 JSON。摘要失败只降低审计可观测性，不改变 Tool 执行结果。

## 数据流

```text
模型提出 Tool 调用
  -> ToolExecutor 查找工具并执行策略校验
  -> 计算现有参数哈希和幂等键
  -> ToolArgumentAuditSummarizer 生成结构摘要
  -> 保存 RUNNING 工具审计记录
  -> 执行 Tool 并更新终态
```

摘要组件只影响 `arguments_redacted_json`，不改变模型输入、Tool 实际输入或结果。

## 数据库兼容

正式 MySQL 继续使用：

```sql
arguments_redacted_json JSON NULL
```

测试环境中的 JPA 字段继续映射为 `TEXT`，避免扩大 H2 兼容改动。测试必须再次使用 Jackson 解析
持久化字符串，以证明它满足 MySQL JSON 列的内容要求。

历史记录不转换。旧记录中的非 JSON 内容由后续独立数据治理任务决定是否清理，本阶段不做批量更新。

## 测试设计

### 摘要组件测试

1. `KnowledgeSearchInput` 只产生字段名、查询长度、文件数量和元素类型。
2. 输出不包含查询原文和任何文件标识。
3. 字符串、数字、布尔值、集合、Record、普通对象和 `null` 均按规则生成摘要。
4. 动态 Map 只记录 `fieldCount`，不记录动态键名。
5. 超过深度、字段数或输出大小时产生受限摘要或固定降级 JSON。
6. 无法序列化的对象返回固定降级 JSON，且方法不抛异常。
7. 每个非空返回值都能被 Jackson 再次解析。

### ToolExecutor 持久化测试

1. 使用包含明显秘密值的输入执行测试 Tool。
2. 从 `HarnessToolCallRepository` 读取 `argumentsRedactedJson`。
3. 断言该值是合法 JSON，包含输入类型和长度，但不包含秘密值。
4. 验证工具仍成功执行，现有哈希、幂等和状态行为不回归。

### 全量验证

定向测试通过后运行 `make verify`，并执行 `git diff --check`。

## 验收标准

- 所有有参 Tool 审计只保存合法的结构摘要 JSON。
- 无参 Tool 保存数据库 `NULL`。
- 摘要中不存在任何实际参数值。
- 摘要异常不会阻断 Tool 执行。
- 正式 MySQL `JSON` 列不再接收 Java 对象字符串表示。
- 参数哈希、幂等键和 Tool 执行结果保持现有语义。
- 定向测试与仓库完整验证通过。
