# ADR-001：Harness 安全边界与首期范围

- **状态**：已接受
- **日期**：2026-07-04
- **决策者**：myllm 项目组
- **关联**：[HarnessEngineering改造方案.md](../../HarnessEngineering改造方案.md)

## 背景

myllm 将向 Agent Harness 控制平面演进：模型提议动作，Harness 决定能否执行、预算、审批与验证。必须在首期明确工具与数据边界，避免模型或编码 Agent 绕过业务 Service 造成不可逆损害。

## 决策

### 1. Harness 定义

Harness Engineering 指 **应用内** 的运行环境、工具注册、上下文、状态、策略、验证与审计。**不是** Harness.io CI/CD 产品。

### 2. 执行权归属

- Tool 执行循环由 **应用** `HarnessOrchestrator` 控制。
- Spring AI `ChatClient` 使用 ToolCallback，但 **`internalToolExecutionEnabled=false`**（或等价配置），禁止框架自动连续执行工具。
- 模型输出必须是结构化 `CALL_TOOL` 或 `FINAL`；解析失败不执行任何工具。

### 3. 永久禁止（模型与 ToolRegistry 均不得注册）

| 类别 | 示例 |
|------|------|
| Shell | `Runtime.exec`、bash、任意进程 |
| 任意 SQL | 直连 MySQL/PostgreSQL JDBC 写操作 |
| 任意 Cypher | 模型生成图写入 |
| 任意 URL | 未在白名单内的 HTTP 出站 |
| 文件系统 | 读写应用服务器任意路径 |
| 密钥 | 读取环境变量、配置文件中的 secret |

业务写操作（删除文件、重试任务、回填图）**仅**通过具名 Adapter + 风险策略 +（WRITE 以上）人工审批，首期全部关闭。

### 4. 首期 v1 工具 allowlist

| 工具 | 风险 | v1 |
|------|------|-----|
| `knowledge.search` | READ_ONLY | ✅ |
| `file.list` | READ_ONLY | ✅ |
| `file.task-status` | READ_ONLY | ✅ |
| `graph.status` | READ_ONLY | ✅ |
| `request.log-summary` | SENSITIVE_READ | ❌（需角色，H6 前不开） |
| `upload.retry` | WRITE | ❌ |
| `graph.retry` | WRITE | ❌ |
| `file.delete` | DESTRUCTIVE | ❌（模型永不可申请；人工 API 保留） |

### 5. Chat 集成模式

```yaml
harness.chat.mode: SHADOW  # 默认
```

- `OFF`：仅现有 `ChatService`
- `SHADOW`：用户答案不变，异步 Harness 对比
- `ACTIVE`：白名单流量走 Harness（H5 后启用）

### 6. 敏感数据日志

默认 **不** 持久化完整 Prompt、Tool 参数、Tool 结果原文。MySQL 存哈希、长度、Token、脱敏摘要；大内容进 MinIO Artifact（可选加密）。

现有 `transaction_log` 全文保留策略在 H5 前增加配置开关与保留期，新 Harness 表从第一天遵守本 ADR。

### 7. 不上传流水线 Agent 化

`document_upload_task` / `document_graph_index_task` 保持 **DETERMINISTIC_WORKFLOW**。Harness H7 仅统一 lease/fencing，不改为模型决定解析顺序。

## 理由

- 与 OpenAI Harness Engineering 经验一致：约束需 **机械执行**，不能仅靠 Prompt。
- 现有 fail-soft RAG 已成熟；Harness 先 Shadow 可观测，再 ACTIVE。
- 跨存储无分布式事务；写工具审批滞后于只读 Tool 与持久化 Run。

## 后果

### 正面

- 非法 Tool 拦截可测（目标 100%）。
- Shadow 不影响现网 SLA。
- 编码 Agent 有明确禁止清单。

### 负面

- 需自建状态机与持久化（不用 Temporal 首期）。
- WRITE 能力上线晚于只读 Tool。

## 合规检查（PR Review）

- [ ] 新增 Tool 是否在 ADR allowlist 或已更新 ADR？
- [ ] 是否绕过 `ToolExecutor` 直接调 Service 写方法？
- [ ] 是否将 Prompt/参数原文写入日志表？
- [ ] 是否让模型决定上传/构图事务顺序？

## 参考

- [HarnessEngineering改造方案.md](../../HarnessEngineering改造方案.md) §一、§十、§十三、§二十三
