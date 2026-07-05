# H1：Harness Domain 与持久化 — 执行计划

- **状态**：已完成（保留在 active 目录，待拆分 H3/H4 计划时归档）
- **前置**：H0 完成（AGENTS、ADR、DDL 草案）
- **目标里程碑**：H1 验收 — 旧 Worker 无法覆盖新 lease；重复领取无重复副作用

## 目标

建立 Harness 控制平面数据模型与状态守卫，为 H2 Tool Registry 和 H3 Model Loop 提供持久化基础。

## 非目标

- 不实现完整 Orchestrator 业务逻辑（属 H3）
- 不接入 Chat SHADOW（属 H5）
- 不改造 `document_upload_task` 表结构（属 H7）

## 验收条件

1. `harness_run` 等 6 张表可迁移创建。
2. Run 状态转换违反规则时抛出明确错误码。
3. `lease_token` 不匹配时 UPDATE 影响行数为 0，旧 Worker 无法回写。
4. 预算字段（max_steps、model_call_count 等）在 Domain 层可校验。
5. 单元测试覆盖状态机合法/非法转移 ≥ 90% 分支。

## 风险

| 风险 | 缓解 |
|------|------|
| JPA 与手写 lease SQL 冲突 | lease 更新用 `@Modifying` 原生 SQL + 乐观锁 version |
| 表过大 | step/event 只存摘要，大 payload 走 artifact |

## 数据库变更

- 脚本：[src/main/resources/db/harness_schema.sql](../../src/main/resources/db/harness_schema.sql)
- 开发环境：可先 `ddl-auto=update` 验证实体，生产显式执行 SQL

## 实施步骤

### Step 1 — Domain 枚举与错误码（0.5d）

```text
com.example.myllm.harness.domain/
  RunStatus, StepStatus, StepType, ToolRisk, ToolCallStatus
  ApprovalStatus, RunType, HarnessErrorCode
```

### Step 2 — JPA 实体（1d）

```text
HarnessRun, HarnessStep, HarnessToolCall
HarnessApproval, HarnessEvent, HarnessArtifact
```

关联：`run_id` UUID；`HarnessRun.version` 乐观锁。

### Step 3 — Repository + Lease SQL（1d）

```java
// 领取运行：仅 QUEUED → RUNNING
UPDATE harness_run SET status='RUNNING', lease_owner=?, lease_token=?, lease_expires_at=?
WHERE run_id=? AND status='QUEUED'

// 心跳/完成：必须 lease_token 匹配
WHERE run_id=? AND lease_token=?
```

### Step 4 — StateTransitionGuard（1d）

```text
RunStateMachine.validate(from, to)
StepStateMachine.validate(...)
```

非法转移 → `HarnessErrorCode.INVALID_STATE_TRANSITION`。

### Step 5 — BudgetManager（0.5d）

```text
checkStepBudget(run)
checkModelCallBudget(run)
checkToolCallBudget(run)
checkWallTime(run)
```

超预算 → `BUDGET_EXHAUSTED`，不调用模型。

### Step 6 — HarnessRunService 骨架（1d）

```text
createRun(definitionId, objective, clientRequestId)  // 幂等 clientRequestId
claimNextQueuedRun(workerId)
heartbeat(runId, leaseToken)
completeRun / failRun / cancelRun
appendEvent(runId, type, payloadRedacted)
```

### Step 7 — 单元测试（1d）

- 状态机表驱动测试
- lease 过期后重新领取，旧 token 提交失败
- 重复 `clientRequestId` 返回同一 run

## 验证命令

```bash
mvn test -Dtest='com.example.myllm.harness.**'
make test-fast
```

## 回滚

1. `harness.enabled=false`（配置项，H3 前无运行时影响）
2. 未执行 SQL 则无表；已执行则 `DROP TABLE` 逆序（仅 dev）

## 进度日志

| 日期 | 决策/进展 |
|------|-----------|
| 2026-07-04 | H0 完成，DDL 与本文档创建 |
| 2026-07-04 | H1：6 实体 + Repository + HarnessRunService + lease/fencing 测试通过 |
| 2026-07-04 | H2：ToolRegistry + ToolExecutor + 4 只读 Adapter + 拦截/幂等测试 |
| 2026-07-05 | H3：补齐 Run API、正确的 CANCELLED 语义及模型/工具阻塞期间独立心跳 |
