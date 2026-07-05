# AGENTS.md — myllm 编码 Agent 入口地图

> 短地图，不是百科。细节见链接文档。

## 项目是什么

Spring Boot RAG 知识库 Demo：对话 + 混合检索（Dense/BM25/Graph）+ 文档入库 + 可选 Neo4j 图索引。

## 先读这些

| 文档 | 用途 |
|------|------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 组件分层与依赖方向 |
| [HarnessEngineering改造方案.md](HarnessEngineering改造方案.md) | Harness 改造总方案 |
| [docs/design-docs/ADR-001-harness-boundaries.md](docs/design-docs/ADR-001-harness-boundaries.md) | Harness 安全边界（必读） |
| [待办优化方案.md](待办优化方案.md) | 功能状态与优先级 |
| [README.md](README.md) | 启动、端口、开发命令 |

## 代码布局

```text
src/main/java/com/example/myllm/
  controller/     HTTP 入口，不含业务逻辑
  service/          业务编排（Chat、RAG、入库、构图）
  entity/           MySQL JPA 实体
  repository/       Spring Data JPA
  config/           Spring 配置与 Properties
  support/          解析、分块、检索、MinIO、DocForge 等
  harness/          Harness 控制平面（Run、Tool、Worker、API），勿侵入现有 service

src/main/resources/
  application*.yml  配置（local 凭据 gitignore）
  db/*.sql          手工迁移脚本
  static/index.html 单页 UI

evals/              评测数据集（JSONL + manifest）
docs/               架构、ADR、执行计划
```

## 依赖方向（必须遵守）

```text
controller → service → support
harness/api → harness/application → harness/port → harness/adapter → service

禁止：support → controller；harness → 直接写 SQL/Cypher；模型绕过 ToolRegistry 调业务写接口
```

## 常用命令

```bash
make help              # Make 目标说明
make doctor            # 环境与服务探测
make test-fast         # 单元测试（不含集成）
make verify            # test-fast + 文档链接检查（H8 扩展）
make restart-java      # 重启 myllm
make restart-docforge  # 重启 DocForge
make status-all        # 全部服务状态
mvn test               # 同 test-fast
```

## Harness 改造当前阶段

- **H0/H1/H2（已完成）**：仓库地图、持久化状态机、lease/fencing、只读 Tool
- **H3（已完成）**：应用控制 Tool Loop、Worker、运维 API
- **H4（进行中）**：Context 预算、不可信证据边界、引用校验与 Repair；Artifact 溢出待补
- **v1 范围**：只读 Tool + Shadow Chat，WRITE/DESTRUCTIVE 关闭

## 修改前检查

1. 是否影响 RAG 在线路径？→ 默认 fail-soft，Shadow 优先
2. 是否新增写工具？→ 需 ADR + 审批，首期禁止
3. 是否跨 MySQL/PG/MinIO/Neo4j？→ 不用单事务，补偿需幂等
4. 是否存 Prompt/Tool 参数原文？→ 默认只存哈希与摘要

## 测试

- 单元测试：`src/test/java`，运行 `make test-fast`
- 评测集：`evals/`，H8 接入 CI
- 集成测试：Testcontainers（Neo4j 已有），Harness E2E 待 H8
