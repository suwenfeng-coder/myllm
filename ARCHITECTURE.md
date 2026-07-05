# myllm 架构概览

> 高层组件与数据流。实施 Harness 时以此文档为依赖方向基准。

## 系统上下文

```text
                    ┌─────────────┐
  Browser ─────────►│ myllm :8080 │
                    └──────┬──────┘
                           │
     ┌─────────────────────┼─────────────────────┐
     ▼                     ▼                     ▼
  Ollama :11434      PostgreSQL :5434        MySQL
  (chat + embed)     pgvector + BM25         JPA 审计/任务
     │                     │                     │
     │              MinIO :9000            Neo4j :7687
     │              原文件/解析结果          可选图索引
     ▼
  DocForge :8000
  Python 解析
```

## 应用分层

| 层 | 包 | 职责 |
|----|-----|------|
| API | `controller` | HTTP、校验、状态码 |
| 应用服务 | `service` | 对话、RAG、入库、构图、日志 |
| 领域支撑 | `support.*` | 解析、分块、检索算法、外部客户端 |
| 持久化 | `entity` + `repository` | MySQL |
| 配置 | `config` | Bean、Properties、异常处理 |
| Harness（规划） | `harness.*` | Run 编排、Tool、策略、验证、审计 |

**规则**：Controller 不直接访问 JdbcTemplate / Neo4j Driver；Harness Adapter 调用现有 Service，不复制 RAG 逻辑。

## 核心链路

### RAG 对话

```text
POST /api/chat
  → ChatService
  → QueryRewriteService
  → RagRetrievalService (Dense + BM25 [+ Graph Shadow])
  → ReciprocalRankFusion → RetrievalCandidateRanker
  → FileEmbeddingService.buildRagPrompt
  → ChatClient (单次调用)
  → transaction_log + rag_call_log
```

### 文档入库

```text
POST /api/files/upload/async
  → document_upload_task (MySQL)
  → FileUploadTaskWorker
  → Parse → Clean → Chunk → Batch Embed → PostgreSQL
  → MinIO + 审计日志
  → [可选] document_graph_index_task → Neo4j
```

### Harness（目标架构，H1+）

```text
POST /api/harness/runs
  → HarnessRunService → HarnessOrchestrator
      → ContextAssembler / PolicyEngine / BudgetManager
      → ModelGateway (app-controlled, 非自动 Tool 循环)
      → ToolRegistry → Adapter → 现有 Service
      → ValidatorChain
  → harness_run / step / tool_call / event (MySQL)
  → artifact (MinIO)

/api/chat mode=SHADOW：现网 ChatService 不变，异步跑 Harness 对比
```

## 存储分工

| 存储 | 主数据 | Harness 用途 |
|------|--------|--------------|
| PostgreSQL | chunk、vector、BM25 | 不存 Harness 状态 |
| MySQL | 日志、任务、Harness run | Run/Step/Tool/Event/Approval |
| MinIO | 文件、解析 Markdown | 大型 Artifact、验证报告 |
| Neo4j | 结构图（派生） | 只读 graph.status Tool |

## 两种 Run 类型

| 类型 | 示例 | 说明 |
|------|------|------|
| `AGENT_LOOP` | knowledge-assistant-v1 | 模型提议 Tool，Harness 控制执行 |
| `DETERMINISTIC_WORKFLOW` | 上传、构图、补偿 | 固定阶段，模型不参与事务顺序 |

上传/构图 **不** 改为自由 Agent Loop。

## 与 Agent Harness 六元组

| 元组 | 落点 |
|------|------|
| E 执行循环 | HarnessOrchestrator、Worker |
| T 工具 | ToolRegistry + Adapter |
| C 上下文 | ContextAssembler、Token 预算 |
| S 状态 | harness_* 表、lease/fencing |
| L 生命周期 | 状态机、Shadow、取消、Saga（P2） |
| V 验证 | ValidatorChain、evals/ |

## 外部依赖

- Spring Boot 3.4、Java 17、Spring AI 1.0.0
- 向量：bge-m3（1024 维）；对话：qwen3:8b（Ollama）
- ParadeDB pg_search（BM25，端口 5434）

## 相关文档

- [HarnessEngineering改造方案.md](HarnessEngineering改造方案.md)
- [docs/design-docs/ADR-001-harness-boundaries.md](docs/design-docs/ADR-001-harness-boundaries.md)
- [docs/exec-plans/active/H1-harness-domain-and-persistence.md](docs/exec-plans/active/H1-harness-domain-and-persistence.md)
