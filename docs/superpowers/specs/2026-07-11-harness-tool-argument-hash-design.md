# Harness 工具参数规范化哈希设计

## 背景

`ToolExecutor` 当前通过 `String.valueOf(input)` 生成参数文本，再对该文本计算 SHA-256。该做法依赖
Java 对象的 `toString()` 实现，不能稳定表达结构化参数：

- `Map.toString()` 保留遍历顺序，同一组字段仅顺序不同就会产生不同哈希。
- 数组默认 `toString()` 可能包含对象身份信息，相同内容在不同实例上不稳定。
- 不同结构可能形成相同的 Map 字符串，参数边界不明确。
- Map、Record 与其他对象的文本表示规则不同，后续重构 `toString()` 也会改变哈希。

当前哈希同时写入 `harness_tool_call.arguments_hash`，并在没有显式幂等键时参与自动幂等键生成。
未来 H6 审批还要求审批只对参数哈希完全一致的工具调用有效，因此哈希必须是稳定、无歧义且机械执行的协议，
不能继续依赖调试文本。

生产调用链已经确认：`HarnessActionParser` 将模型参数转换为 JSON 兼容的 Map、List 和标量，
`HarnessOrchestrator` 再把原始参数 Map 传给 `ToolExecutor`。当前在线编排使用 `toolStepId` 作为显式幂等键，
自动幂等键尚未进入该路径。

## 目标

1. 语义相同且仅对象字段顺序不同的参数必须产生相同哈希。
2. 数组顺序、字段缺失与空值、整数与小数表示等有意义差异必须保留。
3. 哈希材料必须无歧义，禁止回退到 `String.valueOf` 或任意对象 `toString()`。
4. 同一次工具调用的参数哈希最多计算一次，并同时供审计和自动幂等键使用。
5. 规范化失败必须 fail-closed，不能执行工具或写入不可信哈希。
6. 原始参数和规范 JSON 不得进入日志、异常消息或数据库。
7. 保持当前显式幂等键、工具输入归一化、审计摘要和数据库结构不变。

## 非目标

- 不把原始参数提前转换为工具声明的 Record 后再计算哈希。
- 不合并字段缺失与显式 `null`，也不合并数值 `1` 与 `1.0`。
- 不引入 HMAC、服务端密钥或新的配置项。
- 不迁移、回填或双读历史参数哈希和自动幂等键。
- 不实现 H6 审批 API、审批页面或审批重放校验。
- 不增加显式幂等键与参数哈希冲突检测；该问题后续单独设计。
- 不修改 `HarnessActionParser`、工具输入 Record、JPA 实体、DDL 或迁移脚本。

## 等价边界

本阶段采用保守的“原始 JSON 结构等价”定义：

| 输入差异 | 哈希关系 | 原因 |
|----------|----------|------|
| 对象字段顺序不同 | 相同 | 字段顺序不是 JSON 对象语义 |
| 嵌套对象字段顺序不同 | 相同 | 每一层对象都递归排序 |
| 数组元素顺序不同 | 不同 | 数组顺序属于参数语义 |
| 字段缺失与字段值为 `null` | 不同 | 保留模型实际提交的结构 |
| `null`、空字符串、空对象 | 不同 | 三者运行时含义不同 |
| 数字 `1` 与 `1.0` | 不同 | 不引入工具类型相关的数值合并 |
| 数字 `1` 与字符串 `"1"` | 不同 | JSON 类型不同 |
| Map 与 Record 产生相同 JSON 树 | 相同 | 哈希只绑定规范 JSON，不绑定 Java 类名 |

哈希对象始终是进入 `ToolExecutor` 的原始参数。这样不会因工具 Record 的默认值、未知字段忽略或
Jackson 类型绑定改变审批边界，也不会移动现有输入校验时机。

## 方案比较

### 方案一：独立 ToolArgumentHasher

新增单职责组件，按固定 Java 输入域直接写出规范 JSON，递归排序、施加安全上限并计算 SHA-256。

优点：协议边界集中、可独立测试、不会继续扩大 `ToolExecutor` 职责，也不会修改全局 Jackson 配置。
缺点：新增一个生产组件和一组测试。

### 方案二：在 ToolExecutor 内联规范化

直接把递归排序和摘要逻辑写入 `ToolExecutor`。

优点：文件数量少。缺点：执行器已经承担策略、幂等、审计、超时和结果大小控制，继续加入规范化协议会降低
可读性并增加测试耦合。

### 方案三：仅启用 Jackson 排序序列化

通过 `SORT_PROPERTIES_ALPHABETICALLY` 和 `ORDER_MAP_ENTRIES_BY_KEYS` 排序后直接序列化。

优点：实现最短。缺点：Map、Record、`ObjectNode` 和自定义序列化器的行为边界不统一，且修改共享
`ObjectMapper` 可能影响应用其他 JSON 输出。

本阶段采用方案一。

## 组件设计

### ToolArgumentHasher

在 `com.example.myllm.harness.application` 新增 Spring 组件：

```java
public String hash(Object input)
```

组件不接收应用共享 `ObjectMapper`，也不使用自定义 Jackson 模块、命名策略或任意对象序列化器。
内部使用专用 `JsonFactory` 与 `JsonGenerator`，按本设计定义的 Java 输入域直接写出规范 JSON，避免应用
Jackson 配置或未来自定义序列化器静默改变 v1 协议。

专用 Factory 显式使用 UTF-8、关闭非 ASCII 字符转义且不启用美化输出；每个 Generator 显式保持
`WRITE_BIGDECIMAL_AS_PLAIN` 关闭。NaN 与 Infinity 在进入 Generator 前拒绝，不依赖 Jackson 的兼容写法。
这些设置与固定测试向量共同锁定 v1 字节协议。

该组件只公开最终哈希，不公开规范 JSON、JSON 树或中间字节，避免调用方误记录敏感参数。

### 固定 Java 输入域

只有以下输入可参与 v1 哈希：

| Java 输入 | 规范 JSON |
|-----------|-----------|
| `null` | JSON `null` |
| `String`、`Character`、`Enum` | JSON 字符串；Enum 使用 `name()` |
| `Boolean` | JSON 布尔 |
| `Byte`、`Short`、`Integer`、`Long`、`BigInteger` | JSON 整数 |
| 有限 `Float`、`Double`、`BigDecimal` | JSON 小数，保留对应数字类型的 Jackson 输出 |
| `Map<String, ?>` | JSON 对象，字段名递归排序 |
| `List<?>`、Java 对象数组和基本类型数组 | JSON 数组，保持原顺序 |
| Java Record | JSON 对象，字段名使用 Record Component 名称并排序 |
| 标准 `JsonNode` | 按节点类型递归写出 |

任意普通 POJO、Set、其他 Iterable、非字符串 Map 键、未知 `Number` 子类、`BinaryNode`、`POJONode`、
`MissingNode` 和其他非标准节点均固定失败。Record Component 也按同一输入域递归检查，因此 Record 内嵌
非字符串 Map 键不能绕过约束。Record 访问器抛出异常时固定失败。

### 固定安全上限

规范化过程使用代码常量限制输入，并在遍历和写出期间执行，而不是先构建完整中间树再检查：

| 限制 | 固定值 | 计数规则 |
|------|--------|----------|
| 最大深度 | 32 | 根节点深度为 0，每进入一层对象或数组加 1 |
| 最大节点数 | 10,000 | 对象、数组和每个标量值都计为一个节点 |
| 规范 JSON 最大字节数 | 1,048,576 | 使用 UTF-8 编码后的最终规范 JSON 字节数 |

每次进入值节点前同时检查深度和累计节点数。Map、List、Java 数组、Record、`ObjectNode` 和 `ArrayNode`
在分配排序字段或遍历元素前，先用已知大小检查剩余节点预算；超过预算立即失败，不创建字段排序集合。
遍历路径使用对象身份集合检测循环引用，离开当前容器时移除，允许同一不可变值在不同分支重复出现。

规范 JSON 直接写入最多允许 1 MiB 的摘要输出流。输出流每接收一段字节就同时更新计数和
`MessageDigest`；超过上限立即抛出内部固定异常。规范 JSON 不会先完整写入 `byte[]`、String 或临时文件。

## v1 规范化算法

`ToolArgumentHasher` 按以下顺序执行：

1. 创建 SHA-256，并先写入 UTF-8 域字符串 `harness-tool-arguments-v1` 与一个零字节。
2. 创建直接连接该摘要的有界输出流，再由专用 `JsonGenerator` 向输出流写紧凑 JSON。
3. 递归处理输入；进入每个值前检查最大深度、累计节点数、循环引用和允许的 Java 或 JsonNode 类型。
4. Map、Record 和 `ObjectNode` 字段按 Java `String` 自然顺序写出；字段值继续递归处理。
5. List、Java 数组和 `ArrayNode` 元素保持原顺序写出；元素继续递归处理。
6. 字符串、有限数字、布尔和空值按固定类型写出；NaN 与 Infinity 在写出前拒绝。
7. 有界输出流累计规范 JSON 字节数，超过 1 MiB 立即失败；任何中间 JSON 内容均不对外暴露。
8. 关闭并刷新生成器后，返回 64 位小写十六进制摘要。

对象排序采用确定性的字符串自然顺序；数组不会排序。算法不使用平台默认字符集、默认 Locale、换行符、
应用 `ObjectMapper` 或对象 `toString()`。Jackson 版本升级若改变生成器输出，固定向量测试必须先失败并要求
显式评估，不能静默改变 v1。

固定测试向量：

```text
规范 JSON: {"fileIds":["file-1","file-2"],"query":"制度依据"}
哈希材料:  harness-tool-arguments-v1 + 0x00 + 规范 JSON UTF-8
SHA-256:  ddc0ee6120d602754b8251a3528ac27dae57580b079ae59dfb169623d14f1980
```

固定向量用于锁定算法版本；未来如果必须改变规范化规则，应使用新的域版本，不得静默改变 v1 结果。

## ToolExecutor 集成

`ToolExecutor` 构造函数新增 `ToolArgumentHasher` 依赖，并删除现有静态 `hashInput` 实现及对应摘要导入。

执行顺序调整为：

```text
查找工具
  -> 策略校验
  -> 判断是否持久化审计
  -> 持久化路径计算一次 argumentsHash
  -> 解析显式幂等键，或使用 toolName + ":" + argumentsHash
  -> 查询成功的幂等记录
  -> 创建审计记录并直接写入已计算的 argumentsHash
  -> 按现有规则归一化输入、执行工具和更新结果
```

没有 `runId` 的临时调用不会查询幂等记录、写审计或计算参数哈希，保持当前临时执行兼容性。

`createAuditRecord` 改为接收已经计算好的 `argumentsHash`，不得再次序列化或摘要输入。
`resolveIdempotencyKey` 改为接收参数哈希：显式键仍只做现有的去空白处理；只有显式键缺失时才使用
`toolName + ":" + argumentsHash`。

本阶段不改变现有幂等命中条件。显式幂等键复用时是否同时校验参数哈希，作为下一项独立优化处理。

## 失败与安全边界

任何参数转换、规范化、限制检查、序列化或摘要失败都转换为：

```text
错误码: VALIDATION_FAILED
错误消息: 工具参数无法安全规范化
```

异常不得包含输入值、字段内容、Jackson 原始异常消息或 cause。失败发生在幂等查询、审计写入和工具执行之前，
因此失败调用满足：

- 工具执行次数为 0。
- `harness_tool_call` 新增记录数为 0。
- 不使用固定 fallback 哈希，也不继续执行。
- 不把规范 JSON、原始序列化字节或输入对象写入日志。

参数哈希是控制面安全数据，必须 fail-closed；`arguments_redacted_json` 只是辅助审计摘要，继续保持现有
fail-soft 语义。两者失败策略不同，测试必须分别构造，不能再用同一个异常对象同时触发两条路径。

裸 SHA-256 不解决低熵参数的离线枚举风险。本阶段沿用现有 SHA-256 与数据库结构；HMAC 和密钥轮换若进入
威胁模型，应另行设计，不能与本次稳定性修复混合。

## 兼容性与历史数据

- 新算法会改变新写入记录的 `arguments_hash`，这是有意的协议升级。
- 当前代码没有读取 `arguments_hash` 的业务路径，无需迁移或双读历史记录。
- 当前生产编排始终使用 `toolStepId` 显式幂等键，自动键算法变化不会影响在线重放命中。
- 历史自动幂等键不做兼容命中；项目当前没有生产调用依赖该路径。
- 显式幂等键的值、数据库唯一约束和审计表结构保持不变。
- 无 `runId` 的临时工具调用保持不计算哈希，避免扩大任意测试或诊断调用的输入限制。

## 测试设计

### ToolArgumentHasherTests

新增独立单元测试，至少覆盖：

1. 顶层对象键顺序不同，哈希相同。
2. 多层嵌套对象的键顺序不同，哈希相同。
3. 数组顺序不同，哈希不同。
4. `null`、空字符串和空对象哈希各不相同。
5. 数字 `1` 与 `1.0` 哈希不同，数字 `1` 与字符串 `"1"` 哈希不同。
6. 两个会产生相同旧 Map 文本的不同结构在 v1 下哈希不同。
7. Map 与产生同一 JSON 树的 Record 哈希相同。
8. 固定测试向量得到规定的 64 位小写摘要。
9. 顶层与 Record 内嵌的非字符串 Map 键、NaN、Infinity、普通 POJO、Set、非标准节点和异常 Record
   访问器固定失败。
10. 循环容器、深度 33、节点数超过 10,000、规范 JSON 超过 1 MiB 固定失败；超限期间不创建完整
    规范 JSON 中间副本。
11. 失败消息为固定中文内容，且不包含测试秘密值。

### ToolExecutorTests

在现有 JPA 测试中增加或调整：

1. 两个字段顺序不同但结构相同的 Map 使用空显式幂等键调用同一测试工具，工具只执行一次，数据库只有
   一条成功记录。
2. 上述调用保存的 `argumentsHash` 相同，自动幂等键后缀与该哈希一致。
3. 提供显式幂等键时数据库仍保存原键，同时保存 v1 参数哈希。
4. 持久化调用遇到规范化失败时抛出固定 `VALIDATION_FAILED`，工具执行次数和审计记录数均为 0。
5. 无 `runId` 的临时调用不触发哈希，保持现有执行行为。
6. 审计摘要 fallback 测试改用独立测试替身触发，只验证摘要 fail-soft，不再同时制造哈希失败。
7. 使用计数型 `ToolArgumentHasher` 测试替身明确断言：每次持久化自动键调用哈希一次、每次持久化显式键
   调用哈希一次、无 `runId` 调用哈希零次。
8. 使用 Repository Spy 断言哈希失败发生在任何幂等查询或审计写入之前，工具执行次数也为 0。
9. 现有策略、结果大小、空结果、显式幂等重放和结构化审计测试继续通过。

### 完整验证

```bash
mvn -q -Dtest=ToolArgumentHasherTests,ToolExecutorTests test
make verify
git diff --check
```

验收命令必须在项目隔离工作树中执行：

```text
/Users/suwenfeng/Documents/code/myllm/.worktrees/minio-object-key-integrity
```

## 预计文件范围

- 新增：`src/main/java/com/example/myllm/harness/application/ToolArgumentHasher.java`
- 新增：`src/test/java/com/example/myllm/harness/application/ToolArgumentHasherTests.java`
- 修改：`src/main/java/com/example/myllm/harness/application/ToolExecutor.java`
- 修改：`src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java`
- 新增：本设计文档与后续实施计划。

不得出现 Parser、工具输入 Record、实体、DDL、迁移脚本或文件删除。

## 验收标准

- 对象字段顺序不再影响参数哈希，数组和标量差异仍被保留。
- 固定向量生成规定的 v1 SHA-256。
- 自动幂等键与审计记录复用同一次哈希结果。
- 不再存在 `String.valueOf(input)` 参数哈希兜底。
- 规范化失败时不执行工具、不写审计、不泄露原始参数。
- 显式幂等键、临时调用、工具输入归一化和审计摘要语义保持不变。
- 定向测试、完整仓库验证、文档链接和差异格式检查全部通过。
