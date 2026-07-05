# myllm

本地 LLM 学习与 RAG 知识库 Demo：Spring Boot + Ollama + pgvector + DocForge。

## 功能概览

- 对话与 RAG 检索（Dense + BM25 + RRF）
- 文档上传向量化（同步 / 异步任务）
- 多格式解析（本地 + DocForge Docling/Maker）
- 可选 Neo4j 结构图索引
- 单页 Web UI：`http://localhost:8080`

## 环境要求

| 组件 | 版本 / 说明 |
|------|-------------|
| Java | 17+ |
| Maven | 3.9+ |
| Ollama | `qwen3:8b`、`bge-m3` |
| MySQL | 业务库（JPA） |
| PostgreSQL + pgvector | 向量库，推荐 ParadeDB `rag_hybrid:5434` |
| MinIO | 对象存储（可选） |
| DocForge | Python 解析服务 `:8000`（可选） |
| Neo4j | 图索引（可选，默认关闭） |

## 快速启动

```bash
cd /path/to/myllm

# 环境探测（Java/Maven/端口/文档）
make doctor

# 一键启动基础设施 + DocForge + myllm
/bin/bash ./scripts/start-all.sh

# 或分别控制（见 make help）
make restart-java
make restart-docforge
make status-all
```

应用地址：`http://localhost:8080`

## 配置

| 文件 | 说明 |
|------|------|
| `src/main/resources/application.yml` | 主配置 |
| `src/main/resources/application-local.yml` | 本地凭据（gitignore，从 `.example` 复制） |
| `src/main/resources/application-ollama.yml` | Ollama 模型 |
| `src/main/resources/application-datasource.yml` | MySQL 数据源 |

### 关键端口

| 服务 | 端口 |
|------|------|
| myllm | 8080 |
| PostgreSQL (rag_hybrid) | 5434 |
| MinIO API | 9000 |
| MinIO Console | 9001 |
| DocForge | 8000 |
| Neo4j Bolt | 7687 |
| Ollama | 11434 |

### SOCKS 代理

本机若配置了网络代理，`mvn spring-boot:run` 可能无法直连 PostgreSQL 或虚拟组网中的 Ollama。
`pom.xml` 已禁用 SOCKS 代理，并将 localhost、局域网及 `100.*` 地址加入 HTTP 代理直连名单。

```text
-DsocksProxyHost= -DsocksProxyPort= -Dhttp.nonProxyHosts=localhost|127.*|100.*|*.local|169.254/16|*.169.254/16
```

手动启动时同样需要禁用 SOCKS。

## 开发

```bash
make test-fast          # 单元测试
make verify             # 测试 + 文档链接检查
mvn spring-boot:run     # 启动应用（pom 已禁用 SOCKS 代理）
make help               # 全部 Make 目标
```

## 文档

- [AGENTS.md](AGENTS.md) — 编码 Agent 入口地图
- [ARCHITECTURE.md](ARCHITECTURE.md) — 架构概览
- [docs/index.md](docs/index.md) — 文档索引
- [待办优化方案.md](待办优化方案.md) — 功能状态与优化路线图
- [scripts/README.md](scripts/README.md) — 启停脚本说明
- [项目结构与DocForge多模式解析分析.md](项目结构与DocForge多模式解析分析.md)
- [Neo4j集成详细配置与开发计划.md](Neo4j集成详细配置与开发计划.md)
- [HarnessEngineering改造方案.md](HarnessEngineering改造方案.md) — Agent 运行时与仓库级 Harness 改造路线
- [docs/design-docs/ADR-001-harness-boundaries.md](docs/design-docs/ADR-001-harness-boundaries.md) — Harness 安全边界

## P0 生产化能力（近期）

- 批量 embedding + JDBC batch 入库
- pgvector HNSW 索引
- 入库失败跨存储补偿清理
- 删除联动 pgvector / MySQL 审计 / MinIO
- Token 级分块（bge-m3 估算）
- Spring AI 1.0.0 GA
