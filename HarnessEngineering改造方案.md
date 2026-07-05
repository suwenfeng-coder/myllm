# myllm Harness Engineering 详细改造方案

> 制定日期：2026-07-04  
> 适用项目：myllm  
> 术语边界：本文的 Harness Engineering 指 AI Agent 外围的运行环境、上下文、工具、状态、策略、验证、反馈与审计体系，不是 Harness.io CI/CD 产品。

## 一、结论

当前项目已经具备若干 Harness 零件：异步上传任务、进度状态、超时恢复、批量向量化、跨存储补偿、Neo4j 构图任务、Graph Shadow、RAG 日志和服务控制脚本。但是这些能力分别固化在上传、构图和聊天服务中，尚未形成统一的 Harness 控制平面。

本次改造不应简单理解为“给 ChatClient 增加几个 `@Tool`”。推荐新增一个轻量、可持久化、应用控制的 Harness 内核：

```text
模型负责提出下一步动作
Harness 负责决定动作能否执行、何时执行、执行多少次、是否需要审批、如何验证和何时停止
```

推荐分两条线实施：

1. **运行时 Harness**：在应用中增加状态机、工具注册、预算、策略、人工审批、验证循环、事件和审计。
2. **仓库级 Harness**：让代码库、文档、测试、运行环境和质量规则对人和编码 Agent 都可读、可执行、可验证。

首期只开放只读工具并运行 Shadow，不允许模型直接删除文件、写数据库、触发任意 URL 或执行 Shell。

## 二、最新项目结构与处理逻辑

### 2.1 当前组件

```text
myllm
├── controller
│   ├── ChatController
│   ├── FileEmbeddingController
│   ├── GraphController
│   └── RequestLogController
├── service
│   ├── ChatService
│   ├── RagRetrievalService
│   ├── Bm25RetrievalService
│   ├── FileEmbeddingService
│   ├── VectorChunkBatchWriter
│   ├── FileUploadTaskService / Worker
│   ├── FileIngestionCleanupService
│   ├── GraphIndexTaskService / Worker
│   ├── GraphIndexingService
│   ├── LlmGraphKnowledgeExtractor
│   └── Neo4jGraphRetrievalService
├── support
│   ├── document / parser / docforge
│   ├── chunking
│   ├── retrieval
│   ├── graph
│   └── upload
├── entity / repository
│   ├── 对话和 RAG 日志
│   ├── 上传任务
│   ├── 文档清洗与存储日志
│   └── Neo4j 构图任务
├── PostgreSQL / pgvector / BM25
├── MySQL
├── MinIO
├── Neo4j
└── DocForge Python 服务
```

### 2.2 文档入库链路

```text
HTTP 上传
  -> 同步处理或创建 document_upload_task
  -> FileUploadTaskWorker 领取
  -> Local / Docling / Maker 解析
  -> MinIO 保存原文和解析结果
  -> 数据清洗
  -> Token/结构/语义分块
  -> 批量 Embedding
  -> JDBC Batch 写入 PostgreSQL
  -> 创建 Neo4j 构图任务
  -> 可选实体关系抽取
  -> Neo4j 结构图和实体图
```

### 2.3 RAG 链路

```text
ChatController
  -> ChatService
  -> QueryRewriteService
  -> Dense + BM25 + Graph
  -> 加权 RRF
  -> 阈值、去重、文件范围和单文档限额
  -> 组装 Prompt
  -> 单次 ChatClient 调用
  -> transaction_log + rag_call_log
```

### 2.4 已具备的 Harness 基础

| 能力 | 当前实现 | Harness 评价 |
| --- | --- | --- |
| 持久化任务 | 上传任务、构图任务 | 可复用，但状态机分散 |
| 异步 Worker | `@Scheduled` + MySQL 锁 | 可复用，需要 lease/fencing |
| 进度 | 上传 phase/percent/message | 仅业务特定，不是通用事件 |
| 超时 | 定时将超时任务置失败 | 缺少心跳，存在旧 Worker 回写竞态 |
| 重试 | 构图指数退避 | 上传任务缺少统一重试策略 |
| 工具能力 | RAG、文件、构图服务均可封装 | 尚无 Tool Registry 和风险分级 |
| 验证 | JSON 解析、维度校验、阈值过滤 | 缺少统一 Validator 链和修复循环 |
| 审计 | 对话/RAG/任务日志 | 无 run/step/tool/approval 统一关联 |
| 降级 | BM25、Graph、DocForge 降级 | 分散在业务类中 |
| 预算 | TopK、文件大小、超时等局部配置 | 无单次运行统一预算 |

## 三、当前主要缺口

### 3.1 ChatService 是一次性调用，不是受控 Agent Loop

当前流程只有“准备上下文 -> 调用模型 -> 返回答案”，不存在：

- 模型动作结构化协议。
- 最大步数和最大模型调用次数。
- Tool 执行前策略检查。
- Tool 执行后的结果校验。
- 自我修复或重新规划。
- 等待人工审批与恢复执行。
- 可恢复的运行状态。

### 3.2 异步任务存在并发状态风险

上传任务被定时器标记超时后，原 Worker 仍可能继续运行并回写成功；取消任务会删除临时文件，但执行中的 Worker 未持有协作式 cancellation token。需要引入：

- `lease_owner`、`lease_token`、`lease_expires_at`。
- 心跳续租。
- fencing token，旧 Worker 无权回写新状态。
- `cancel_requested` 与阶段安全点。
- 明确、可机械验证的状态转换表。

### 3.3 跨存储补偿不是持久化 Saga

`@Transactional` 不能同时覆盖 MySQL、PostgreSQL、MinIO 和 Neo4j。当前补偿方法一旦进程退出，补偿进度可能丢失。需要将补偿步骤持久化并幂等执行。

### 3.4 工具没有统一风险边界

业务服务可以直接执行查询、重试、删除和回填，但没有统一元数据：

- 输入/输出 Schema。
- READ_ONLY / WRITE / DESTRUCTIVE 风险等级。
- 超时与最大结果大小。
- 幂等键。
- 是否需要人工审批。
- 参数和结果脱敏规则。

### 3.5 缺少 Context Engineering

当前系统 Prompt、用户输入、RAG 内容和工具输出没有统一上下文预算、优先级和压缩策略。检索文档也未在 Harness 层明确标记为“不可信数据”，存在间接 Prompt Injection 风险。

### 3.6 缺少统一验证闭环

当前校验集中在局部格式与数值边界。最终回答没有机械验证：

- 引用是否来自实际命中 Chunk。
- 文件名、Chunk 和 contentHash 是否仍存在。
- 是否回答了用户目标。
- 是否包含未证实的业务结论。
- Tool 结果是否被模型错误解释。
- 失败后是否允许重新规划，最多几次。

### 3.7 仓库对编码 Agent 的可读性不足

当前没有短小的项目级 `AGENTS.md`、`ARCHITECTURE.md`、结构化 `docs/`、执行计划目录、架构规则测试和 CI 工作流。根 README 还出现命令名称与 Makefile 不一致等文档漂移信号。

## 四、目标架构

```text
API / UI
  -> HarnessRunService
  -> HarnessOrchestrator
      -> DefinitionRegistry
      -> ContextAssembler
      -> ModelGateway
      -> ActionParser
      -> PolicyEngine
      -> ApprovalService
      -> ToolRegistry / ToolExecutor
      -> ValidatorChain
      -> BudgetManager
      -> RunStateStore
      -> EventPublisher
  -> MySQL 状态与事件
  -> MinIO 大型 Artifact
  -> Micrometer / OpenTelemetry

Tool Adapters
  -> RagRetrievalService
  -> FileEmbeddingService
  -> FileUploadTaskService
  -> GraphIndexTaskService
  -> RequestLogQueryService
```

运行分为两种类型：

1. `AGENT_LOOP`：模型在受控范围内选择只读或审批后的工具，例如知识库研究助手。
2. `DETERMINISTIC_WORKFLOW`：固定阶段、固定转移，例如文档入库、构图和补偿；模型只能参与特定步骤，不能决定基础事务顺序。

不要用一个自由 Agent Loop 取代确定性的上传流水线。

## 五、推荐实现方案与方案比较

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 直接在 `ChatClient` 增加 `.tools(...)` | 快、代码少 | 难以审批、持久化、预算和完整审计 | 仅适合 Demo |
| 自研轻量 Harness + Spring AI ToolCallback | 与现有 Spring 服务契合，可控、可渐进 | 需要实现状态机和持久化 | **推荐首选** |
| Spring Statemachine | 状态表达完整 | 引入较重，业务持久化仍需自建 | 暂不采用 |
| Temporal / Camunda | 长任务、重试、可视化成熟 | 部署和运维复杂度明显增加 | 多实例和大规模后评估 |
| LangGraph4j 等 Agent 框架 | Agent 图编排开发快 | 框架语义、持久化和版本锁定成本 | 先做 PoC，不作为核心依赖 |

项目当前使用 Spring AI `1.0.0`。Tool 使用 `ToolCallback`，但关键执行循环应设置为应用控制，而不是让模型框架不受约束地自动执行；未来升级 Spring AI 2.x 时再评估 `ToolCallingAdvisor`。

## 六、建议代码结构

新增独立 bounded context，不继续堆入现有 `service` 包：

```text
com.example.myllm.harness
├── api
│   ├── HarnessRunController
│   ├── HarnessApprovalController
│   └── HarnessEventController
├── domain
│   ├── HarnessRun
│   ├── HarnessStep
│   ├── HarnessToolCall
│   ├── HarnessApproval
│   ├── HarnessArtifact
│   ├── HarnessEvent
│   ├── RunStatus
│   ├── StepStatus
│   ├── ToolRisk
│   └── HarnessAction
├── application
│   ├── HarnessRunService
│   ├── HarnessOrchestrator
│   ├── HarnessWorker
│   ├── ContextAssembler
│   ├── BudgetManager
│   ├── PolicyEngine
│   ├── ApprovalService
│   ├── ValidatorChain
│   └── RecoveryService
├── port
│   ├── ModelGateway
│   ├── HarnessTool
│   ├── ToolRegistry
│   ├── ArtifactStore
│   └── HarnessEventSink
├── adapter
│   ├── model/SpringAiModelGateway
│   ├── tool/RagSearchTool
│   ├── tool/FileListTool
│   ├── tool/FileTaskStatusTool
│   ├── tool/GraphStatusTool
│   ├── tool/UploadRetryTool
│   ├── tool/FileDeleteTool
│   └── storage/MinioHarnessArtifactStore
└── config
    ├── HarnessProperties
    ├── HarnessDefinitionRegistry
    └── HarnessConfiguration
```

现有服务作为 Adapter 后面的业务实现，不把 Harness 类型侵入 `RagRetrievalService`、`FileEmbeddingService` 等核心业务服务。

## 七、运行定义

每种 Harness 以版本化 YAML 定义，定义文件和代码一起进入版本控制：

```yaml
id: knowledge-assistant
version: 1
run-type: AGENT_LOOP
model-profile: local-reasoning

budget:
  max-steps: 8
  max-model-calls: 6
  max-tool-calls: 10
  max-input-tokens: 30000
  max-output-tokens: 6000
  max-wall-time: 120s
  max-tool-result-bytes: 65536

tools:
  allow:
    - knowledge.search
    - file.list
    - file.task-status
    - graph.status
  deny:
    - file.delete
    - shell.execute
    - sql.execute

verification:
  require-citations: true
  require-source-exists: true
  max-repair-attempts: 2

approval:
  WRITE: REQUIRED
  DESTRUCTIVE: REQUIRED
  EXTERNAL: REQUIRED
```

运行创建时保存 `definition_id`、`version` 和内容 SHA-256。定义更新不能改变已开始运行的行为。

## 八、状态机

### 8.1 Run 状态

```text
CREATED
  -> QUEUED
  -> PLANNING
  -> RUNNING
  -> WAITING_APPROVAL
  -> VERIFYING
  -> SUCCEEDED
  -> FAILED
  -> CANCELLED
  -> TIMED_OUT
```

允许的恢复：

```text
FAILED(retryable) -> QUEUED
WAITING_APPROVAL -> RUNNING / CANCELLED
RUNNING(lease expired) -> QUEUED
```

### 8.2 Step 类型

```text
INTAKE
CONTEXT_BUILD
MODEL_DECISION
POLICY_CHECK
APPROVAL
TOOL_EXECUTION
VERIFICATION
REPAIR
FINAL_RESPONSE
COMPENSATION
```

### 8.3 并发控制

每次领取运行时生成不可复用的 `lease_token`：

```text
UPDATE harness_run
SET lease_owner=?, lease_token=?, lease_expires_at=?, status='RUNNING'
WHERE run_id=? AND status='QUEUED'
```

后续心跳、步骤提交、成功和失败都必须包含：

```text
WHERE run_id=? AND lease_token=?
```

旧 Worker 即使继续执行，也无法覆盖已被重新领取的运行状态。

## 九、数据模型

### 9.1 harness_run

```text
run_id UUID PK
definition_id
definition_version
definition_hash
run_type
status
objective
conversation_id
requester_id（预留）
current_step
max_steps
model_call_count
tool_call_count
input_tokens
output_tokens
cancel_requested
lease_owner
lease_token
lease_expires_at
final_output_preview
final_artifact_id
error_code
error_message
created_at / started_at / finished_at / updated_at
version（乐观锁）
```

### 9.2 harness_step

```text
step_id
run_id
sequence_no
step_type
status
attempt
decision_summary
input_hash
output_hash
model_provider / model_name
input_tokens / output_tokens / duration_ms
error_code / error_message
started_at / finished_at
UNIQUE(run_id, sequence_no)
```

只保存简短决策摘要，不保存模型隐藏思维链。

### 9.3 harness_tool_call

```text
tool_call_id
run_id / step_id
tool_name / tool_version
risk_level
status
arguments_redacted_json
arguments_hash
idempotency_key
result_preview
result_artifact_id
result_hash
duration_ms
approval_id
error_code / error_message
UNIQUE(tool_name, idempotency_key)
```

### 9.4 harness_approval

```text
approval_id
run_id / tool_call_id
status: PENDING/APPROVED/REJECTED/EXPIRED
risk_summary
requested_action
arguments_redacted_json
requested_by / decided_by（预留）
expires_at / decided_at
decision_comment
```

### 9.5 harness_event

```text
event_id
run_id
sequence_no
event_type
payload_redacted_json
created_at
UNIQUE(run_id, sequence_no)
```

供 SSE、页面时间线和故障重放使用。

### 9.6 harness_artifact

大型上下文、Tool 结果、验证报告和最终报告写入 MinIO：

```text
artifact_id
run_id / step_id
artifact_type
bucket / object_path
content_type
size_bytes
sha256
retention_until
created_at
```

## 十、Tool Harness

### 10.1 统一接口

```java
public interface HarnessTool<I, O> {
    ToolDescriptor descriptor();
    Class<I> inputType();
    ToolResult<O> execute(ToolExecutionContext context, I input);
}
```

`ToolDescriptor` 至少包含：

```text
name
version
description
riskLevel
inputSchema
outputSchema
timeout
retryPolicy
idempotent
approvalRequired
maxResultBytes
redactionPolicy
```

### 10.2 首期工具清单

| 工具 | Adapter | 风险 | 首期状态 |
| --- | --- | --- | --- |
| `knowledge.search` | `RagRetrievalService` | READ_ONLY | 开放 |
| `file.list` | `FileEmbeddingService.listFiles` | READ_ONLY | 开放 |
| `file.task-status` | `FileUploadTaskService` | READ_ONLY | 开放 |
| `graph.status` | `GraphIndexTaskService` | READ_ONLY | 开放 |
| `request.log-summary` | `RequestLogQueryService` | SENSITIVE_READ | 审批或管理员 |
| `upload.retry` | 上传任务服务 | WRITE | 后续审批开放 |
| `graph.retry` | 构图任务服务 | WRITE | 后续审批开放 |
| `graph.backfill` | GraphController 对应服务 | WRITE | 后续审批开放 |
| `file.delete` | `FileIngestionCleanupService` | DESTRUCTIVE | 最后开放、双重确认 |

永久禁止模型直接使用：

```text
任意 Shell
任意 SQL/Cypher
任意本地文件路径
任意 URL 请求
读取应用环境变量和密钥
绕过业务 Service 直接访问数据库
```

### 10.3 Tool 执行顺序

```text
JSON Schema 校验
  -> Tool 是否在 definition allowlist
  -> 风险策略检查
  -> 预算检查
  -> 是否需要审批
  -> 幂等键检查
  -> 超时/熔断包装
  -> 执行
  -> 输出 Schema 校验
  -> 脱敏和大小限制
  -> Artifact 持久化
  -> 事件和指标
```

## 十一、Context Harness

上下文按固定优先级构建：

```text
1. 不可覆盖的系统安全策略
2. Harness definition 和完成条件
3. 当前用户目标
4. 已审批的约束和选择
5. 运行状态摘要
6. 最近有效步骤
7. RAG/Graph 证据
8. Tool 输出
9. 历史低优先级摘要
```

预算策略：

- 预留输出 Token 和工具定义 Token。
- RAG Chunk 按相关性、文件多样性和证据覆盖选择。
- Tool 结果超过阈值写 Artifact，只把摘要送入模型。
- 每 3～4 步生成结构化运行摘要，不滚动累积完整对话。
- 摘要包含事实、已完成动作、未解决问题和约束，不包含自由发挥。
- 每段外部文档标记为 `UNTRUSTED_EVIDENCE`，文档内的“忽略系统指令”等内容不得改变 Harness 策略。

## 十二、Model Harness

建议使用应用控制的模型循环：

```text
HarnessOrchestrator
  -> 构造 Prompt + 可用工具定义
  -> ChatModel，internalToolExecutionEnabled=false
  -> 读取结构化 tool calls 或 final response
  -> PolicyEngine 决定是否执行
  -> ToolExecutor 执行
  -> Tool 结果作为下一步 observation
  -> 达到完成、预算或失败终止条件
```

原因：自动工具循环不方便在每个工具调用之间插入持久化、审批、预算和 fencing 校验。

模型输出必须是以下两类之一：

```json
{"action":"CALL_TOOL","tool":"knowledge.search","arguments":{},"summary":"需要检索制度依据"}
```

```json
{"action":"FINAL","answer":"...","citations":[],"summary":"已基于证据完成回答"}
```

解析失败不执行任何工具，最多进行一次格式修复；仍失败则运行失败或降级为现有普通 Chat。

## 十三、Policy 与人工审批

### 13.1 风险等级

```text
READ_ONLY       可自动执行，仍受预算和数据范围约束
SENSITIVE_READ  可能读取日志或个人信息，需要角色检查
WRITE           改变任务或索引状态，默认人工审批
DESTRUCTIVE     删除或不可逆操作，强制审批和幂等确认
EXTERNAL        发送外部请求或消息，强制审批
```

### 13.2 审批原则

- 审批页面展示动作、脱敏参数、影响范围、回滚方式和过期时间。
- 审批只对参数哈希完全一致的 Tool Call 有效。
- 模型修改参数后必须重新审批。
- 审批有时效，过期后运行进入 `WAITING_APPROVAL` 或取消。
- `file.delete` 首期只允许 API 人工调用，模型不得申请。

## 十四、Verification Harness

Validator 分三层：

### 14.1 机械校验

- JSON/DTO Schema。
- Tool Call 引用的文件和 Chunk 是否存在。
- 引用 contentHash 是否与当前 PostgreSQL 一致。
- 输出长度、敏感字段和禁止内容。
- 运行预算和状态转换。

### 14.2 业务校验

- 答案引用是否覆盖关键结论。
- 文件限定是否得到遵守。
- Graph 关系是否具有 evidenceChunkIds。
- “未命中”时不得伪造制度依据。
- 删除、重试、回填动作是否与当前任务状态兼容。

### 14.3 模型校验

可选使用独立 Validator Prompt，但不能作为唯一安全门：

- groundedness。
- answer relevance。
- citation correctness。
- 是否满足完成条件。

验证失败：

```text
可修复 -> REPAIR，最多 2 次
不可修复 -> FAILED
证据不足 -> 返回明确“不足以回答”
预算耗尽 -> BUDGET_EXHAUSTED，不继续调用模型
```

## 十五、可观测性和安全审计

增加 `spring-boot-starter-actuator`、Micrometer Observation 和 OpenTelemetry 导出能力。

核心指标：

```text
harness_runs_total{definition,status}
harness_run_duration_seconds
harness_steps_total{type,status}
harness_model_calls_total{provider,model,status}
harness_tool_calls_total{tool,risk,status}
harness_tool_duration_seconds{tool}
harness_approval_wait_seconds{risk}
harness_budget_exhausted_total{budget_type}
harness_validation_failures_total{validator}
harness_repair_attempts_total
harness_lease_recoveries_total
```

Trace 层级：

```text
run
  -> context.build
  -> model.call
  -> policy.check
  -> approval.wait
  -> tool.execute
  -> verify
```

默认不记录完整 Prompt、Tool 参数和 Tool 结果。Spring AI 的 Prompt/Completion 和 Tool 参数观测都必须保持敏感数据导出关闭；仅记录哈希、长度、Token、模型、工具名和脱敏摘要。

当前 `transaction_log` 保存完整用户输入、输出和系统 Prompt，需要增加：

- 可配置保留期。
- 敏感字段脱敏。
- 管理接口权限。
- 是否保存原文的独立开关。
- 大内容转 MinIO 加密 Artifact，MySQL 只保存摘要和哈希。

## 十六、API 与页面

### 16.1 API

```text
POST   /api/harness/runs
GET    /api/harness/runs/{runId}
GET    /api/harness/runs/{runId}/steps
GET    /api/harness/runs/{runId}/events        SSE
POST   /api/harness/runs/{runId}/cancel
POST   /api/harness/approvals/{approvalId}/approve
POST   /api/harness/approvals/{approvalId}/reject
GET    /api/harness/runs/{runId}/artifacts
```

创建请求：

```json
{
  "definitionId": "knowledge-assistant",
  "objective": "比较两份信用卡制度中的额度规则",
  "fileIds": ["..."],
  "clientRequestId": "幂等请求ID"
}
```

### 16.2 页面

新增 Harness 运行面板：

- 当前状态、步骤、Token、模型调用和 Tool 调用预算。
- 事件时间线。
- Tool 参数脱敏预览。
- 审批/拒绝。
- 引用和 Artifact。
- 取消运行。
- 失败原因和可重试状态。

禁止页面展示隐藏思维链；只展示决策摘要、动作和证据。

## 十七、与现有功能的渐进集成

### 17.1 Chat

保留 `/api/chat`，增加配置：

```yaml
harness:
  enabled: false
  chat:
    mode: OFF # OFF | SHADOW | ACTIVE
```

- `OFF`：完全使用当前 ChatService。
- `SHADOW`：当前答案照常返回，同时异步运行只读 Harness，对比结果，不执行写工具。
- `ACTIVE`：指定用户或请求进入 Harness。

### 17.2 上传任务

第一阶段不替换 `document_upload_task`。先为其增加 lease/fencing/cancel token；再提供 `upload.task-status` 等 Tool Adapter。

第二阶段抽取通用 `DurableJobLease` 和 `StateTransitionGuard`，让上传任务和构图任务复用，不强行迁移已有数据表。

### 17.3 构图任务

- 实体抽取作为可观测的模型步骤，记录 promptVersion/model/Token/验证结果。
- 每批 Chunk 创建 checkpoint，失败从批次恢复。
- JSON 使用严格 DTO/Schema，不再只依赖截取首尾花括号。
- 图写入仍是确定性步骤，模型不能直接生成 Cypher。

### 17.4 RAG

`knowledge.search` Tool 调用现有 `RagRetrievalService`，返回结构化：

```text
queryRewrite
retrievalMode
sources
scoresByChannel
citations
fallbackReasons
```

Harness 不复制 Dense/BM25/Graph 逻辑。

## 十八、仓库级 Harness

### 18.1 知识地图

新增：

```text
AGENTS.md                         短入口和约束地图
ARCHITECTURE.md                   高层组件和依赖方向
docs/
├── index.md
├── architecture/
├── design-docs/
├── product-specs/
├── exec-plans/
│   ├── active/
│   └── completed/
├── generated/
│   ├── mysql-schema.md
│   ├── postgres-schema.md
│   └── neo4j-schema.md
├── runbooks/
├── reliability.md
├── security.md
└── quality-score.md
```

`AGENTS.md` 只做目录，不写成超长百科。

### 18.2 机械约束

Maven 增加：

- Maven Enforcer：Java/Maven 版本和依赖收敛。
- Spotless：格式化。
- ArchUnit：Controller -> Application -> Domain/Port -> Adapter 依赖方向。
- JaCoCo：覆盖率趋势，初期不设置不现实的总覆盖率硬门槛。
- Failsafe：集成测试与单元测试分离。
- SQL/YAML/Markdown 链接检查。
- Secret 扫描。

### 18.3 可重复环境

- Docker Compose 使用固定镜像版本，不使用浮动 tag。
- `make doctor` 输出 Java、Maven、Docker、模型、端口、数据库和索引状态。
- `make test-fast`、`make test-integration`、`make verify`、`make eval-rag`。
- 每个命令有超时和明确退出码。
- 文档中的命令由 CI 执行，防止 README 漂移。

### 18.4 执行计划

复杂改动必须创建版本化执行计划，包含：

```text
目标
非目标
验收条件
风险
数据库变更
实施步骤
验证命令
回滚方式
进度和决策日志
```

## 十九、评测 Harness

### 19.1 测试层级

1. 纯单元测试：状态机、预算、策略、解析、幂等键。
2. Model Replay：使用固定模型响应 Fixture，确定性重放 Agent Loop。
3. Tool Contract：输入 Schema、输出 Schema、错误和超时。
4. Testcontainers：MySQL、PostgreSQL、Neo4j；MinIO 使用隔离实例。
5. 端到端：上传 -> 构图 -> Harness 检索 -> 最终引用。
6. Live Model Eval：夜间或手工执行，不作为每次 PR 的不稳定门禁。

### 19.2 评测集

```text
evals/
├── rag-retrieval.jsonl
├── answer-grounding.jsonl
├── tool-selection.jsonl
├── prompt-injection.jsonl
├── approval-policy.jsonl
├── cancellation-recovery.jsonl
└── fixtures/
```

### 19.3 指标

```text
任务成功率
首次成功率
平均修复次数
Tool 选择准确率
非法 Tool 拦截率
审批绕过率（目标 0）
引用正确率
Groundedness
Recall@K / MRR / nDCG
平均 Token / Tool Calls / 延迟
超时恢复后重复副作用率（目标 0）
```

## 二十、配置建议

```yaml
harness:
  enabled: false
  worker:
    enabled: true
    poll-interval-ms: 1000
    lease-duration-ms: 30000
    heartbeat-interval-ms: 10000
    max-concurrency: 2
  budget:
    max-steps: 8
    max-model-calls: 6
    max-tool-calls: 10
    max-wall-time-ms: 120000
    max-input-tokens: 30000
    max-output-tokens: 6000
    max-tool-result-bytes: 65536
  policy:
    write-tools-enabled: false
    destructive-tools-enabled: false
    external-tools-enabled: false
    approval-timeout-ms: 1800000
  context:
    max-rag-chunks: 8
    max-tool-observation-chars: 12000
    summary-every-steps: 3
  verification:
    enabled: true
    max-repair-attempts: 2
    require-citations: true
  observability:
    store-prompt-content: false
    store-tool-arguments: false
    store-tool-results: false
```

## 二十一、实施里程碑

### H0：基线与边界

- [x] 修正 README/Makefile 命令漂移。
- [x] 建立 `AGENTS.md`、`ARCHITECTURE.md` 和 docs 目录。
- [x] 记录现有 API、表、索引、任务状态和配置基线（见 ARCHITECTURE.md）。
- [x] 建立 Harness ADR，明确不开放 Shell/SQL/Cypher/任意 URL。

**验收**：新工程师或 Agent 可从一个短入口找到架构、运行、测试、数据和约束。

### H1：Harness Domain 与持久化

- [x] 增加 domain 枚举、状态机守卫、预算检查与单元测试。
- [x] 增加 run/event 实体、Repository 与 HarnessRunService（lease/heartbeat/complete/fencing）。
- [x] 增加 step/tool/approval/artifact 实体（JPA，待业务接入）。
- [x] 实现 HarnessWorker 轮询消费 QUEUED 运行。
- [x] 增加并发和崩溃恢复集成测试（多实例场景）。

**验收**：旧 Worker 无法覆盖新 lease；重复领取不会产生重复副作用。

### H2：只读 Tool Registry

- [x] 定义 ToolDescriptor、ToolRegistry 和 ToolExecutor。
- [x] 接入 knowledge.search、file.list、file.task-status、graph.status。
- [x] 实现 allowlist、风险策略、超时、大小限制、脱敏参数哈希与幂等日志。
- [x] 非法工具与 allowlist 外工具拦截单测。
- [ ] Spring 上下文集成测试（真实 Adapter + Testcontainers，H8 补充）。

**验收**：模型只能请求 allowlist 中工具；非法工具调用拦截率 100%。

### H3：应用控制 Model Loop

- [x] 增加 `ModelGateway`，关闭模型内部自动工具执行。
- [x] 实现 HarnessAction 严格解析。
- [x] 实现最大步数、模型调用、Tool 调用、Token 和总耗时预算。
- [x] 实现可恢复步骤循环和事件。

**验收**：Fixture Replay 完全确定；任何循环都能被预算终止。

### H4：Context 与 Verification

- [x] 实现上下文优先级、Token 预算和安全摘要；Artifact 溢出仍待实现。
- [x] 标记并转义 RAG/Tool 内容为不可信证据。
- [x] 实现实际命中引用白名单与最终答案 Validator；contentHash 和文件范围仍待实现。
- [x] 实现最多两次 Repair，并持久化 Step/Event。

**验收**：Prompt Injection 测试不得改变 Tool 策略；无效引用不能进入最终答案。

### H5：API、SSE 和 Shadow

- [ ] 增加 run/status/steps/events/cancel API。
- [ ] 页面增加运行时间线和预算。
- [ ] `/api/chat` 增加 OFF/SHADOW/ACTIVE 路由。
- [ ] Shadow 运行不改变当前用户答案。

**验收**：Shadow 与现网链路隔离，失败不影响 Chat；可完整查看动作与证据。

### H6：审批和写工具

- [ ] 增加审批 API、页面、参数哈希和过期机制。
- [ ] 灰度开放 upload.retry、graph.retry。
- [ ] 做权限、CSRF、重放和审批绕过测试。
- [ ] `file.delete` 继续保持模型不可用，待双重审批成熟后评估。

**验收**：WRITE 无审批无法执行；审批参数被修改后审批自动失效。

### H7：任务可靠性统一

- [ ] 将 lease/fencing/cancel token 应用到上传和构图 Worker。
- [ ] 将跨存储补偿改成持久化 Saga。
- [ ] 图实体抽取增加批次 checkpoint 和严格 Schema。
- [ ] 保留现有业务表，先复用基础设施，不做高风险一次性迁表。

**验收**：进程在任意阶段退出后可恢复；重复副作用为 0；补偿状态可查询。

### H8：仓库 Harness、CI 和 Eval

- [ ] 增加格式、架构、文档、Schema 和 Secret 检查。
- [ ] 建立 replay、集成、E2E 和 live eval 分层。
- [ ] 增加本地可查询指标与 Trace。
- [ ] 定期执行文档和技术债 gardening。

**验收**：`make verify` 一条命令完成确定性质量检查；评测结果可与基线比较。

## 二十二、优先级

### P0

- H0 仓库地图和 ADR。
- H1 run/step/tool/event、状态守卫、预算、lease/fencing。
- H2 只读 Tool Registry。
- H3 应用控制 Tool Loop。
- Prompt/Tool 参数默认不落原文日志。
- 完整的单元和 Replay 测试。

### P1

- H4 Context/Verification。
- H5 Shadow、SSE 和页面。
- H6 WRITE 审批。
- 上传/构图 Worker 引入心跳与 fencing。
- Micrometer/OpenTelemetry。

### P2

- 持久化 Saga。
- Reranker/Validator Model。
- 多实例并发 Worker。
- Temporal/Camunda 可行性评估。
- 多 Agent，仅在单 Agent Harness 稳定后考虑。

## 二十三、首个可投产版本范围

建议首个版本只完成：

```text
knowledge-assistant-v1
只读工具
最多 6 次模型调用
最多 8 步
最多 120 秒
严格引用校验
运行持久化
可取消
完整事件和指标
SHADOW 默认开启
WRITE/DESTRUCTIVE/EXTERNAL 全部关闭
```

不要在第一个版本同时做多 Agent、通用浏览器、Shell、数据库直连、自动删除和外部消息发送。

## 二十四、参考原则

OpenAI 对 Harness Engineering 的关键经验与本方案一致：仓库知识应成为系统记录，`AGENTS.md` 应是地图而非百科；架构约束需要机械执行；Agent 需要能读取 UI、日志、指标和验证反馈；复杂任务计划和反馈循环应作为仓库内的一等工件。

Spring AI 提供 ToolCallback、Tool Calling 和 Micrometer Observation 能力，但工具执行权仍属于应用；本项目需要在框架能力之上增加持久化、策略、预算、审批和验证，才构成完整 Harness。
