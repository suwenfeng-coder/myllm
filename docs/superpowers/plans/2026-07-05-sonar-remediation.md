# Sonar Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 2026-07-05 IDEA Sonar 全项目扫描中除 `secrets:S6697` 外的 72 个问题，并建立项目代码质量规范。

**Architecture:** 按规则根因分批做行为保持型修改。简单静态问题直接最小化修复；空值、事务、并发、正则和复杂度问题通过现有测试或新增回归测试保护；最后使用 IDEA 全项目复扫验收。

**Tech Stack:** Java 17+、Spring Boot 3.4、Spring AI、JUnit 5、Mockito、HTML、原生 JavaScript、SonarQube for IDE。

## Global Constraints

- 不修改或删除 `scripts/secrets.local.sh` 中的明文密码。
- 不使用 `NOSONAR`、批量 `@SuppressWarnings`、规则禁用或扫描排除掩盖问题。
- 不删除任何文件。
- 不改变 Harness v1 只读 Tool 边界和 ADR-001 安全约束。
- 当前 `.git` 元数据不完整，所有 commit 步骤省略，仅保留工作区修改。

---

### Task 1: Java 简单规则与数据模型

**Files:**
- Modify: `src/main/java/com/example/myllm/controller/ChatController.java`
- Modify: `src/main/java/com/example/myllm/dto/ChatRequest.java`
- Modify: `src/main/java/com/example/myllm/service/DocumentParseService.java`
- Modify: `src/main/java/com/example/myllm/support/document/EmptyTableRowFilter.java`
- Modify: `src/main/java/com/example/myllm/eval/support/EvalScoreSupport.java`
- Modify: `src/main/java/com/example/myllm/config/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/example/myllm/harness/adapter/tool/model/KnowledgeSearchCitation.java`
- Modify: `src/main/java/com/example/myllm/harness/adapter/tool/KnowledgeSearchTool.java`
- Modify: `src/main/java/com/example/myllm/eval/controller/ModelEvalController.java`
- Modify: `src/main/java/com/example/myllm/eval/repository/ModelEvalRunRepository.java`
- Modify: `src/main/java/com/example/myllm/eval/service/ModelEvalRunService.java`
- Modify: `src/main/java/com/example/myllm/eval/service/ModelEvalTaskWorker.java`
- Modify: `src/main/java/com/example/myllm/harness/application/PermanentlyDeniedTools.java`
- Modify: `src/main/java/com/example/myllm/harness/adapter/model/SpringAiModelGateway.java`
- Modify: `src/main/java/com/example/myllm/harness/application/ToolPolicyEngine.java`
- Test: `src/test/java/com/example/myllm/eval/support/EvalScoreSupportTests.java`

**Interfaces:**
- `EvalScoreSupport.applyManualScores(ModelEvalItem, EvalItemScoreUpdateRequest)` replaces the eight-parameter score API.
- Existing HTTP and Harness public contracts remain unchanged.

- [ ] **Step 1: Add regression coverage for the parameter object**

Update the existing test to construct `EvalItemScoreUpdateRequest` and call:

```java
EvalScoreSupport.applyManualScores(
        item,
        new EvalItemScoreUpdateRequest(null, null, 5, null, null, null, "人工修正代码分"));
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn -q -Dtest=EvalScoreSupportTests test`

Expected: compilation failure because the parameter-object overload does not exist.

- [ ] **Step 3: Implement the minimal static fixes**

Implement:

```java
private static final String DIRECT = "DIRECT";
```

Use `log.isDebugEnabled()` before computing `chatService.getModelInfo()` for debug output; remove unused imports; simplify boolean returns; remove the redundant `(long)` cast; replace nested ternary snippet construction with independent statements; and route manual scoring through `EvalItemScoreUpdateRequest`.

- [ ] **Step 4: Verify focused tests and compilation**

Run: `mvn -q -Dtest=EvalScoreSupportTests,ChatRequestTests test`

Expected: PASS.

---

### Task 2: Null safety, concurrency and transaction proxy boundaries

**Files:**
- Modify: `src/main/java/com/example/myllm/service/ChatService.java`
- Modify: `src/main/java/com/example/myllm/harness/adapter/model/FixtureReplayModelGateway.java`
- Create: `src/main/java/com/example/myllm/harness/application/HarnessStepTransactionService.java`
- Modify: `src/main/java/com/example/myllm/harness/application/HarnessStepService.java`
- Create: `src/main/java/com/example/myllm/eval/service/ModelEvalItemTransactionService.java`
- Modify: `src/main/java/com/example/myllm/eval/service/ModelEvalItemWriter.java`
- Modify: `src/main/java/com/example/myllm/harness/application/ToolExecutor.java`
- Test: `src/test/java/com/example/myllm/service/ChatServiceTests.java`
- Test: `src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java`

**Interfaces:**
- Transaction collaborators expose explicit methods invoked through injected Spring beans.
- `ToolExecutor.execute` continues returning a typed `ToolResult<?>`, but its internal generic helper methods use method type parameters instead of raw/wildcard tool values.

- [ ] **Step 1: Add null-response regression tests**

Add a `ChatModel` returning `null` and assert `simpleChat` throws a stable `IllegalStateException`.
Add a test Harness tool returning `null` and assert `ToolExecutor` returns `TOOL_EXECUTION_FAILED`.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `mvn -q -Dtest=ChatServiceTests,ToolExecutorTests test`

Expected: failure caused by current nullable response/result dereference.

- [ ] **Step 3: Implement null and generic boundaries**

Validate the complete chain:

```java
if (response == null || response.getResult() == null
        || response.getResult().getOutput() == null) {
    throw new IllegalStateException("模型返回空响应");
}
```

Normalize a null tool result to `ToolResult.failed(...)`; rename restricted variable `record`; remove raw `HarnessTool` use through generic helper methods; simplify `persistAudit && audit != null` after making the audit state structurally non-null in the persistent branch.

- [ ] **Step 4: Move transactional self-invocations**

Move interrupted-step failure updates and item reset/status updates into injected collaborators so calls cross a Spring proxy instead of `this`.

- [ ] **Step 5: Make replay state atomic**

Replace the volatile list plus separate atomic cursor with one immutable state held by `AtomicReference<ReplayState>`:

```java
private record ReplayState(List<String> responses, int cursor) {}
```

Use compare-and-set for response consumption and reset.

- [ ] **Step 6: Verify focused tests**

Run: `mvn -q -Dtest=ChatServiceTests,ToolExecutorTests,HarnessOrchestratorReplayTests,ModelEvalRunServiceTests test`

Expected: PASS.

---

### Task 3: Parser, loop and Harness complexity reduction

**Files:**
- Modify: `src/main/java/com/example/myllm/harness/domain/HarnessActionParser.java`
- Modify: `src/main/java/com/example/myllm/harness/application/HarnessOrchestrator.java`
- Modify: `src/main/java/com/example/myllm/support/document/HeadingLevelRepairer.java`
- Modify: `src/main/java/com/example/myllm/support/document/LayoutTableFlattener.java`
- Test: `src/test/java/com/example/myllm/harness/domain/HarnessActionParserTests.java`
- Test: `src/test/java/com/example/myllm/harness/application/HarnessOrchestratorReplayTests.java`
- Test: `src/test/java/com/example/myllm/support/document/DocumentBlockNormalizerTests.java`

**Interfaces:**
- No public API changes.
- Harness loop extraction uses private records for loop state/outcomes only.

- [ ] **Step 1: Add behavior coverage for headings and parser aliases**

Cover duplicate heading removal, valid existing levels, `sourceId` and `source_id`, malformed citations, and code-fenced JSON.

- [ ] **Step 2: Run focused tests before refactoring**

Run: `mvn -q -Dtest=HarnessActionParserTests,DocumentBlockNormalizerTests,HarnessOrchestratorReplayTests test`

Expected: PASS as characterization tests.

- [ ] **Step 3: Refactor parser and document loops**

Extract citation parsing from `parseFinal`; remove repeated regex evaluation in `inferLevel`; replace multi-`continue` loops with helper predicates and one append path.

- [ ] **Step 4: Split orchestration responsibilities**

Extract methods for:

```java
ModelDecision requestModelDecision(...)
FinalOutcome handleFinalAction(...)
void handleToolCall(...)
ValidationOutcome validateFinalAnswer(...)
```

Keep budget checks, event ordering, step persistence, Repair limits and fail-soft semantics identical.

- [ ] **Step 5: Verify focused tests**

Run: `mvn -q -Dtest=HarnessActionParserTests,DocumentBlockNormalizerTests,HarnessOrchestratorReplayTests test`

Expected: PASS.

---

### Task 4: Deterministic tests and Sonar-compliant exception assertions

**Files:**
- Modify: `src/test/java/com/example/myllm/harness/application/HarnessOrchestratorReplayTests.java`
- Modify: `src/test/java/com/example/myllm/harness/api/HarnessRunControllerTests.java`
- Modify: `src/test/java/com/example/myllm/harness/application/HarnessRunServiceTests.java`
- Modify: `src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java`
- Modify: `src/test/java/com/example/myllm/harness/application/ToolPolicyEngineTests.java`

- [ ] **Step 1: Replace system time**

Use fixed constants such as:

```java
private static final LocalDateTime FIXED_TIME =
        LocalDateTime.of(2026, 7, 5, 12, 0);
```

- [ ] **Step 2: Reduce exception assertion lambdas**

Prepare all arguments before `assertThrows`; lambda bodies contain only the single method invocation expected to throw.

- [ ] **Step 3: Remove unused imports and simplify boolean assertions**

Use `assertTrue`/`assertFalse`; remove unused `ModelGateway`, `RunStatus`, and `ToolIds` imports.

- [ ] **Step 4: Run Harness tests**

Run: `mvn -q -Dtest='com.example.myllm.harness.**' test`

Expected: PASS.

---

### Task 5: `eval.html` JavaScript and accessibility

**Files:**
- Modify: `src/main/resources/static/eval.html`

- [ ] **Step 1: Associate static labels**

Every static control receives an `id`/`for` pair:

```html
<label for="qTitle">标题</label>
<input id="qTitle">
```

- [ ] **Step 2: Fix generated score labels**

Generate unique IDs and labels:

```javascript
const inputId = `score-${item.id}-${key}`;
wrap.innerHTML = `<label for="${inputId}">${DIM_LABELS[key]}</label>
  <input id="${inputId}" ...>`;
```

- [ ] **Step 3: Replace presentation labels**

Change non-form `<label>` elements inside item reports to `<div class="block-label">`.

- [ ] **Step 4: Simplify JavaScript**

Use `replaceAll`, extract nested template fragments, prefer positive conditions and early returns, remove the useless default `{}` where reported, and surface poll errors in the progress UI.

- [ ] **Step 5: Compile resources with the application**

Run: `mvn -q -DskipTests compile`

Expected: PASS.

---

### Task 6: Code quality standard and final verification

**Files:**
- Create: `docs/code-quality-guidelines.md`
- Modify: `AGENTS.md`
- Modify: `docs/index.md`

- [ ] **Step 1: Write the rule guide**

Document all encountered rules with rationale and examples: S2629, S1192, S2259, S1905, S1126, S107, S3077, S1128, S3776, S6541, S8692, S5778, S6809, S5852, S135, S3358, S6213, S1452, S2589, JavaScript S7781/S7735/S4624/S2486/S7744, and Web label rules.

- [ ] **Step 2: Add discoverability links**

Add `docs/code-quality-guidelines.md` to the AGENTS “先读这些” table and `docs/index.md`.

- [ ] **Step 3: Run full verification**

Run:

```bash
make test-fast
make verify
mvn -q -DskipTests compile
```

Expected: all commands exit 0.

- [ ] **Step 4: Re-run IDEA Sonar**

Use **Analyze All Project Files**.

Expected:

- Found 1 issue in 1 file: `scripts/secrets.local.sh` / `secrets:S6697`
- Security Hotspots: 0
- Taint Vulnerabilities: 0
