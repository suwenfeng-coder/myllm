# Sonar 扫描问题修复与代码规范设计

## 背景

2026 年 7 月 5 日 22:44，IntelliJ IDEA 的 SonarQube for IDE 对 `myllm`
执行全项目扫描，共发现 73 个问题，分布在 31 个文件中。扫描未发现
Security Hotspot 或 Taint Vulnerability。

本次修复保留 `scripts/secrets.local.sh` 中的 `secrets:S6697` 明文密码问题，
其余 72 个问题全部修复。明文密码属于已知且临时接受的风险，不通过
`NOSONAR`、规则禁用或假值替换掩盖。

## 目标

1. 修复除 `secrets:S6697` 外的所有当前 Sonar 问题。
2. 保持现有 RAG、文档入库、模型评测和 Harness 行为不变。
3. 对可能改变行为的修复增加回归测试，并执行完整验证。
4. 将本次问题沉淀为项目级代码质量规范，供后续开发和评审遵循。
5. IDEA 全项目复扫后仅允许剩余 1 个已接受的 `secrets:S6697` 问题。

## 非目标

- 本次不修改、删除或轮换 `scripts/secrets.local.sh` 中的明文密码。
- 不新增业务能力，不调整 Harness v1 的只读边界。
- 不进行与当前 Sonar 问题无关的大规模重构。
- 不通过批量抑制、降低规则级别或排除扫描目录实现“清零”。

## 修复策略

采用按根因分批、行为保持优先的混合策略。

### 1. 正确性与安全性

- 对 Sonar 判定为 nullable 的模型响应和 Tool 结果建立显式空值边界。
- 将 Spring `@Transactional` 自调用拆到独立协作者，确保代理事务生效。
- 将可能发生多项操作的异常断言 Lambda 拆为单一可抛异常调用。
- 替换存在多项式回溯风险的正则实现。
- 使用线程安全的状态容器代替仅靠 `volatile` 保护的可变状态。

涉及行为的修复先写最小回归测试并确认失败，再实现修复。

### 2. 复杂度与可维护性

- 将 Harness 编排器和动作解析器中的复杂分支提取为职责单一的私有方法或协作者。
- 使用参数对象降低方法参数数量。
- 提取重复字符串常量。
- 消除嵌套三元表达式、恒真条件、冗余类型转换和冗余 `if/else`。
- 重写循环控制流，使每个循环最多包含一个 `break` 或 `continue`。
- 删除确认未使用的 import。
- 仅在对应日志级别启用时计算代价较高的日志参数。

重构不得改变 Harness 状态机、ToolRegistry、预算、引用校验和 fail-soft 语义。

### 3. 测试代码

- 测试数据使用固定 `Clock` 或固定时间值，不依赖系统时钟。
- 异常断言 Lambda 只保留一个可能抛出运行时异常的方法调用。
- 删除测试中的未使用 import。

### 4. 前端与可访问性

- 为 `eval.html` 的所有表单控件建立有效且有文本的 `<label for="...">` 关联。
- 展示型标题不使用 `<label>`，改用语义合适的普通元素。
- 将只替换一次或多次的字符串操作改为语义明确的 API。
- 展开嵌套模板字符串和难读的否定条件。
- 不吞掉异常；应记录、展示或让异常向上传播。
- 删除无意义的空对象。

### 5. 代码规范落地

新增 `docs/code-quality-guidelines.md`，内容包括：

- Java 空值、日志、事务、并发、异常、复杂度、参数与正则规范。
- JUnit 固定时钟和异常断言规范。
- HTML 表单可访问性及 JavaScript 可读性规范。
- 对应 Sonar Rule Key、禁止写法、推荐写法和评审检查清单。
- 已知例外必须显式记录，禁止用 `NOSONAR` 隐藏。

在 `AGENTS.md` 和 `docs/index.md` 中增加入口，确保后续编码 Agent 和开发者
在修改代码前能够发现该规范。

## 验证

按以下顺序验证：

1. 对行为修复执行对应的定向单元测试，完成红—绿验证。
2. 执行 `make test-fast`。
3. 执行 `make verify`。
4. 必要时执行 Maven 编译，确认主代码和测试代码均可编译。
5. 在 IntelliJ IDEA 中执行 **Analyze All Project Files**。

最终 Sonar 报告必须满足：

- Open Issues：仅剩 `scripts/secrets.local.sh` 的 `secrets:S6697`，共 1 个。
- Security Hotspots：0。
- Taint Vulnerabilities：0。

若出现新问题，则继续按对应规则修复，不扩大规则排除范围。

## 风险控制

- 不删除任何文件。
- 不修改 Harness 的永久禁止清单和 v1 Tool allowlist。
- 不将 Prompt、Tool 参数或 Tool 结果原文新增到持久化日志。
- 对大型方法仅做与 Sonar 根因直接相关的职责拆分。
- 保留现有公开接口；若必须调整内部签名，同步更新全部调用点和测试。

