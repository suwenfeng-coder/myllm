# SonarCloud PR Quality Gate 修复设计

## 背景与根因

Pull Request #1 的 SonarCloud 检查当前报告 14 条开放问题：

- 8 条 `javasecurity:S5145` 日志注入漏洞；
- 5 条 Java 代码味道；
- 1 条 JavaScript 代码味道。

Quality Gate 的直接失败条件是“新代码安全评级为 B，要求至少为 A”。因此，
`eval.html` 中的 `Object.assign` 告警不是本次门禁失败的直接原因；真正降低安全评级的是
8 条日志注入漏洞。其余 6 条代码味道虽未直接触发该安全门禁，也一并修复，避免 PR
保留已知问题。

本地 IntelliJ IDEA 当前打开的是主工作区，而 PR 代码位于
`.worktrees/minio-object-key-integrity`。本地扫描主工作区只看到 1 条问题，不能代表
SonarCloud 按远端 PR 基线计算出的 14 条问题。最终状态以 PR 的 SonarCloud 检查为准。

## 目标

1. 通过代码修改关闭 PR 当前 14 条 SonarCloud 问题。
2. 将新代码安全评级从 B 恢复为 A，使 Quality Gate 通过。
3. 保持对话、文档解析、向量化、DocForge、MinIO 和 Harness 的业务行为不变。
4. 不再把聊天正文、原始上传文件名、文档正文样本或外部错误原文作为日志字段直接记录。
5. 将日志安全要求补充到项目代码质量规范中，防止同类问题回流。

## 非目标

- 不修改 SonarCloud Quality Gate、规则级别或扫描范围。
- 不使用 `NOSONAR`、规则抑制或手工“误报”状态关闭问题。
- 不借本次整改重构无关业务流程或公开接口。
- 不改变 MinIO 对象键、文件标识、Harness 参数哈希和审计语义。
- 不删除文件。

## 方案比较

### 方案一：删除所有不可信日志字段

优点是数据流最简单，SonarCloud 关闭问题的确定性最高，也能最大限度减少隐私泄露。
缺点是会损失部分排障信息，例如文件扩展名和简短失败原因。

### 方案二：所有字段统一做 CR/LF 清洗

优点是保留的运维信息最多。缺点是自定义清洗器未必能被 SonarCloud 的污点分析识别，
而且聊天正文、标题样本等内容即使变成单行，也不适合长期写入 INFO 日志。

### 方案三：抑制规则或手工标记误报

代码改动最少，但不能改善真实日志注入和隐私风险，也会让门禁结果依赖平台状态。
本次不采用。

### 推荐方案：按字段价值混合处理

- 聊天正文、文件名、标题样本、解析警告等非必要内容直接从 INFO 日志移除。
- 扩展名、简短错误原因和出站 multipart 文件名等确有用途的字符串，在使用边界显式
  替换 `\r`、`\n` 后再使用。
- 数值、布尔值、固定枚举、内部生成的标识和耗时指标继续保留。
- 对 MinIO 的 `bytes.length` 污点误判也通过移除日志字段解决，不依赖手工标记。

该方案兼顾门禁确定性、日志安全、隐私保护和排障能力。

## 安全日志修复设计

### 1. ChatService

当前快速对话开始日志通过 `preview(message)` 记录 HTTP 用户输入；同类日志还会记录
原始问题、改写问题、系统提示词和模型回答。截断不能消除日志注入或隐私风险。

修复方式：

- “快速对话开始”日志只保留 `provider` 和 `model`，不再记录消息正文或正文长度。
- RAG 检索失败日志移除原始消息和异常栈，只保留固定失败事件与异常类型；原有
  `ragError` 返回/持久化行为不变。
- RAG 跳过、问题改写、模型调用开始和模型调用完成日志移除原始/改写问题、文件名提示、
  系统提示词和模型回答，只保留意图、策略、模型标识、开关状态和推理指标。
- 同步收窄日志辅助方法的签名，移除不再使用的正文参数；删除不再有调用方的 `preview`
  和 `LOG_PREVIEW_LEN`，避免遗留未使用参数/字段或后续重新引入正文预览。
- 不扩大到对话业务流程、提示词构造或事务日志持久化逻辑。

### 2. DocumentParseService

上传文件名经 `extensionOf` 形成扩展名后进入自动解析成功和候选失败日志；异常消息还会
进入 `fallbackReason`。

修复方式：

- 解析器匹配继续使用原始扩展名，避免改变文件类型判断。
- `conciseMessage` 及其生成的 `fallbackReason` 保持现有值，继续用于返回对象、解析元数据和
  存储记录，不在业务值上做日志清洗。
- 仅在每个日志调用点附近为扩展名、候选失败原因和 `fallbackReason` 创建单行副本，使用
  Sonar 可识别的 `value.replaceAll("[\\r\\n]", "_")` 后写日志，不封装成自定义清洗器。
- 三条重复污点流由同一处成功日志修复共同关闭。

### 3. FileEmbeddingService

向量化完成日志同时记录上传文件名、标题样本、内容类型、解析引擎和清理警告。其中
文件名是当前 SonarCloud 确认的污点源，其他字段也可能来自用户文档或外部解析器。

修复方式：

- 从 INFO 日志移除 `fileName`、`headingSample`、`contentType`、`parserEngine` 和
  `warnings`。
- 删除只为日志生成 `headingSample` 的局部计算，避免形成无效赋值；不改变标题提取、分块或
  返回数据。
- 保留内部生成的 `fileId`、固定解析/分块模式、清理版本、字符数、分片数、向量维度、
  各阶段耗时和图索引入队状态。
- API 返回值和入库元数据仍保留原有文件信息，整改只改变日志输出。

### 4. DocForgeClient

同步解析、异步解析以及同步失败转异步日志均会记录上传文件名。文件名还会作为 multipart
文件名发送给 DocForge。

修复方式：

- 三处运行日志移除文件名和文件大小，只保留白名单引擎、HTTP 状态等可信诊断字段，避免
  文件大小再次触发与 MinIO 相同的跨类型污点误判。
- `sanitizeFilename` 在去除路径后继续显式替换 CR/LF，保护出站 multipart 文件名。
- 将该内部静态方法调整为包可见，以便同包测试直接覆盖路径剥离、空值回退和换行清洗；
  不形成公开 API。

### 5. MinioStorageService

SonarCloud 将文件字节传播到 `bytes.length`，但最终日志参数是整数。为避免依赖平台误报
状态，同时保持整改路径可重复验证，本次直接移除日志中的 `size` 字段。

修复方式：

- 原始文件和解析文件写入成功日志均移除 `size`，保留 bucket 和已经过对象键规则处理的
  object key。
- `MinioStoredObject.size` 返回值、上传字节和对象元数据不变。

## 代码味道修复设计

### Java

1. `ToolArgumentAuditSummarizer`：提取 `"string"` 为类级常量，四处复用，JSON 摘要不变。
2. `ToolArgumentHasher`：将受限标识符参数 `record` 重命名为 `recordValue`，同步方法内引用。
3. `ToolArgumentHasherTests`：在 `assertThrows` 外构造 `ThrowingRecord`，lambda 只保留哈希调用。
4. `ToolExecutorTests`：在 `assertThrows` 外构造 `HashFailureInput`，lambda 只保留执行调用。
5. `DocumentStorageServiceTests`：在 `assertThrows` 外取得 `ParsedDocument`，lambda 只保留存储调用。

涉及完整代码镜像的既有实施计划同步更新，避免文档示例与代码再次分叉。

### JavaScript

`eval.html` 的请求头合并改为：

```javascript
headers: {
  'Content-Type': 'application/json',
  ...requestOptions?.headers
}
```

默认头先声明、调用方头后展开，覆盖顺序与当前 `Object.assign` 一致。页面已经使用模块脚本、
对象展开和可选链，因此不提高浏览器语法要求。本次不额外扩展 `Headers` 实例或 tuple 数组
支持，以免把等价整改变成行为改造。

## 测试与验证

### 回归测试

- `ChatServiceTests` 增加含 CR/LF 的用户输入场景，捕获格式化日志并断言不会出现用户正文、
  伪造日志行或原始换行。
- `DocumentParseServiceTests` 增加带 CR/LF 文件名和异常原因的自动解析场景，断言日志中的
  扩展名和原因保持单行，解析器仍收到原始业务扩展名，返回元数据仍保留原始失败原因。
- 新增 `DocForgeClientTests`，直接验证内部文件名清洗方法对路径、空值和 CR/LF 的处理。
- `MinioStorageServiceTests` 补充 `MinioStoredObject.size` 断言，并继续验证上传调用和对象键，
  证明移除日志字段没有改变返回大小或存储目标。
- 复用现有 Harness 和存储测试验证 5 条 Java 代码味道的等价修改。

测试日志使用 Logback `ListAppender` 捕获格式化消息，不依赖控制台文本或执行顺序；每个
测试在 `finally` 或 `@AfterEach` 中分离 appender 并恢复原日志级别，避免污染后续测试。

### 本地验证顺序

1. 先运行新增日志安全测试，确认修复前能暴露换行或敏感字段问题。
2. 运行受影响类的定向测试。
3. 对 `eval.html` 旧、新请求头表达式运行 Node 合并矩阵，并对模块脚本执行语法检查。
4. 运行 `make verify`。
5. 运行 `git diff --check`。

### 远端验收

推送后以 PR #1 的 SonarCloud 结果为最终依据：

- `javasecurity:S5145` 开放问题数为 0；
- PR 当前 14 条开放问题全部关闭，且没有由本次修改引入的新问题；
- 新代码 Security Rating 为 A；
- SonarCloud Quality Gate 通过。

如果 SonarCloud 仍无法识别某个清洗边界，则只把对应日志字段改为移除，不使用抑制规则、
手工误报状态或降低门禁标准。

## 代码质量规范更新

在 `docs/code-quality-guidelines.md` 的安全规范中补充：

- 用户输入、上传文件名、外部响应、模型输出和异常消息均视为不可信数据。
- INFO/WARN 日志优先记录内部标识、固定状态、数值指标和耗时，不记录正文或标题样本。
- 确需记录的外部字符串必须在日志边界显式清除 CR/LF 并限制长度。
- 不使用 `NOSONAR` 或降低规则等级掩盖日志注入问题。

## 风险与回退

- 日志字段减少可能降低人工排障的可读性，但 `fileId`、对象键、状态、模式和耗时仍可完成
  跨阶段关联。
- 清洗只作用于日志或出站 multipart 文件名，不修改模型输入、解析判断、数据库内容或对象键。
- 若定向测试发现业务行为变化，回退对应代码改动并保留失败测试，再采用更小范围的日志移除。
- 所有修改限定在当前 PR 工作树内；不触碰主工作区，也不删除文件。

## 验收标准

1. 新增和既有测试全部通过，`make verify` 成功。
2. `git diff --check` 无空白错误。
3. SonarCloud PR 开放问题为 0，Security Rating 为 A，Quality Gate 通过。
4. 没有 `NOSONAR`、新增规则抑制、扫描排除或 Quality Gate 配置修改。
5. 业务接口、持久化数据、对象键和 Harness 行为保持不变。
