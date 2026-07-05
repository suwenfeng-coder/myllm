# 项目结构与 DocForge 多模式解析分析

> 分析及改造日期：2026-07-02

## 一、项目分层

```text
浏览器 index.html
  → FileEmbeddingController                 上传、能力查询、检索、删除 API
    → FileEmbeddingService                  文档入库总编排
      → DocumentParseService                auto/local/docling/maker 路由
        ├── LocalDocumentParser             Java 本地解析
        └── DocForgeRemoteDocumentParser    远程解析结果转 ParsedDocument
          → DocForgeClient                  /ready、/engines、同步/异步任务 API
      → DocumentStorageService              原文件和解析后 Markdown 写入 MinIO
      → DocumentCleaningService             保守清洗、结构保留、重复段落处理
      → DocumentChunkingService              fixed/recursive/document/semantic/smart
      → EmbeddingModel                       bge-m3 向量化
      → PostgreSQL/ParadeDB                  chunk、向量、BM25 索引及元数据

聊天请求
  → ChatService
    → RagRetrievalService
      ├── pgvector Dense
      ├── ParadeDB BM25
      └── RRF + 文件名范围 + 去重 + 单文件限额
    → Ollama / OpenAI-compatible ChatClient
```

主要目录职责：

| 目录 | 职责 |
|---|---|
| `controller` | HTTP 参数和状态码转换，不承载解析业务 |
| `service` | 上传、解析、清洗、分块、向量、RAG 与日志编排 |
| `support/document` | 解析器接口、结构化块、解析结果模型 |
| `support/parser` | 本地与 DocForge 解析器适配 |
| `support/docforge` | DocForge HTTP 协议、DTO、同步/异步任务客户端 |
| `support/chunking` | 五种分块策略及元数据生成 |
| `support/retrieval` | 问题改写、文件名解析、RRF 和候选排序 |
| `support/minio` | 原文件及解析结果对象存储 |
| `entity/repository` | MySQL 审计和调用日志 |

## 二、上传与向量化处理逻辑

1. 页面读取 `/api/files/parse-capabilities`，按引擎可用状态和扩展名更新上传白名单。
2. `POST /api/files/upload` 接收 `file`、`chunkStrategy`、`parseMode`。
3. `FileEmbeddingService` 预生成 `fileId`，调用 `DocumentParseService`。
4. 解析结果统一为 `ParsedDocument(fileName, blocks, metadata)`，下游不依赖具体解析引擎。
5. 开启 MinIO 时保存原文件与解析后的 Markdown，并写入 `document_storage_log`。
6. 清洗服务生成清洗前后文本、大小、重复段落统计和告警。
7. 分块服务根据请求策略生成 `DocumentChunk`；smart 会路由到具体策略。
8. 每个 Chunk 使用“来源文件名 + 正文”生成 embedding，并写入 `file_embeddings`。
9. Chunk JSONB 元数据记录请求/实际解析模式、实际引擎和降级原因，支持问题追踪。

当前仍是同步入库：解析、清洗、分块和逐 Chunk embedding 都占用同一个 HTTP 请求线程。

## 三、DocForge Java 调用链

### 3.1 配置与客户端

`DocForgeConfig` 根据 `docforge.*` 创建独立 `RestClient`：

- `base-url`：默认 `http://localhost:8000`
- 可选 Bearer API Key
- 独立连接、读取超时
- 可选启动时 `/ready` 探测

`DocForgeClient` 调用：

| 接口 | 用途 |
|---|---|
| `GET /ready` | 服务总体就绪检查 |
| `GET /v1/engines` | 引擎、输入格式、输出格式和可用状态 |
| `GET /v1/formats` | 兼容旧版单引擎服务 |
| `POST /v1/parse/sync` | 小文件同步解析 |
| `POST /v1/jobs` | 大文件创建异步任务 |
| `GET /v1/jobs/{id}` | 轮询任务状态 |
| `GET /v1/jobs/{id}/result` | 获取解析结果 |

文件不超过 `sync-max-size-mb` 时走同步接口；否则创建任务并轮询，超过
`async-poll-max-wait-ms` 后失败。解析输出固定请求 Markdown，随后由
`FileTextExtractor.parseMarkdown` 转成标题、段落、列表、表格、代码等结构块。

### 3.2 DocForge 服务端现状

服务端位于 `/Users/suwenfeng/Documents/code/python/docforge`：

- `EngineFactory` 根据 `ENABLED_ENGINES=docling,maker` 创建引擎。
- Docling 使用共享 `DocumentConverter` 和锁，支持 PDF、Office、HTML、图片等多格式。
- Maker 实际适配 `marker-pdf`，当前原生只处理 PDF，模型首次使用时懒加载。
- 同步解析已从 FastAPI 事件循环移到共享执行器。
- 大文件任务写入 SQLite，但执行仍依赖进程内后台任务。
- 上传和 URL 下载有大小限制；URL 路径包含 SSRF 校验。
- options 已按引擎和输出格式进行强类型校验。

本机 DocForge 使用 Python 3.11.15，`docling` 与 `marker.convert` 均已安装。

## 四、多模式解析策略

### 4.1 对外模式

| 模式 | 行为 |
|---|---|
| `auto` | 推荐模式，按文件类型和引擎能力自动路由并记录降级 |
| `local` | 强制 Java 本地解析 |
| `docling` | 强制 DocForge Docling 引擎 |
| `maker` | 强制 DocForge Maker 引擎，仅接受 PDF |
| `marker` | 仅为旧调用兼容别名，规范化为 `maker` |

### 4.2 auto 路由

```text
PDF:
  Maker → 失败/不可用 → Docling → 失败

Java 本地已支持格式（txt/md/doc/docx/csv/json/xml/html/log）:
  Local → 失败 → Docling

其他 Docling 格式（xlsx/pptx/图片等）:
  Docling
```

自动模式只在候选解析器声明支持该扩展名时调用。每次解析结果记录：

- `requestedParseMode`
- `appliedParseMode`
- `parserEngine`
- `attemptedParseModes`
- `parseFallbackReason`
- `parseDurationMs`
- `parsePages`

这些字段进入上传响应、MySQL `document_storage_log` 和向量 Chunk JSONB 元数据。

## 五、本次已完成优化

- [x] 模式统一为 `auto/local/docling/maker`，保留 `marker` 兼容别名。
- [x] 增加 PDF 的 Maker → Docling 自动降级和本地格式的 Local 优先策略。
- [x] 显式模式严格校验引擎支持的扩展名，不再把 Docling 格式误当成 Maker 格式。
- [x] `/v1/engines` DTO预留 `enabled/installed/ready/unavailable_reason`，兼容旧响应。
- [x] 引擎与格式能力缓存增加 30 秒 TTL，避免服务重启后永久使用旧能力。
- [x] 页面按单个引擎状态禁用选项，Maker 明确标注 PDF 专用。
- [x] 上传响应区分请求模式、实际模式、实际引擎和降级原因。
- [x] `document_storage_log` 增加六个解析审计字段并完成 MySQL 迁移和旧数据回填。
- [x] Chunk JSONB 增加解析来源元数据。
- [x] 增加 Maker 失败转 Docling、本地优先和解析元数据测试。

## 六、相关优化项

### P0：投产前

1. **DocForge 引擎真实状态**：服务端 `/v1/engines` 应区分 enabled、installed、ready；Maker 仅配置启用但依赖缺失时不能宣告可用。
2. **记录实际执行引擎**：Maker 内部回退 Docling 时，返回 `actual_engine=docling` 与 `fallback_reason`，不能继续标记为 Maker。
3. **上传任务异步化**：Java 当前即使调用 DocForge 异步任务，仍同步轮询并占用 Tomcat 请求线程；应改为上传任务表 + 状态查询/回调。
4. **原子入库与补偿**：MinIO、MySQL、向量库跨三套存储，任一步失败可能留下孤儿对象或部分 Chunk；需要任务状态机和单文件补偿清理。
5. **批量 embedding**：816 个 Chunk 当前逐条调用模型、逐条 INSERT，吞吐和失败恢复较差，应批量 embedding + JDBC batch。

### P1：质量与稳定性

1. **解析质量评测**：建立 PDF 表格、扫描件、双栏、公式、页眉页脚、XLSX 多 Sheet 基准集，对比 Docling/Maker 的结构保真率。
2. **可配置路由**：将 auto 路由从硬编码迁移到配置，例如 PDF 是否优先 Maker、最大页数和超时预算。
3. **Maker 预热和熔断**：启动后异步预热模型；连续失败时临时熔断 Maker，避免每个请求重复触发昂贵失败。
4. **流式转发**：Java `DocForgeClient` 当前 `file.getBytes()` 会复制整个文件，应使用 Resource/InputStream 流式 multipart。
5. **能力负缓存**：DocForge 宕机时短时间缓存失败状态，避免一个 auto 请求连续触发多次 `/engines`、`/formats`。
6. **解析参数模板**：按文档类型提供 Docling OCR、最大页数和 Maker OCR/语言参数模板，并将 options 和版本写入日志。
7. **结构化中间表示**：当前远程结果统一转 Markdown 后再解析，会损失表格单元格、页码、坐标和图片引用；建议优先使用 Docling JSON/DocTags 形成统一 IR。

### P2：规模化与运维

1. DocForge 后台任务改为 Redis/Celery、RQ 或其他可恢复队列，支持进程重启续跑。
2. 按 CPU/MPS/GPU 分离 Docling 与 Maker worker，分别设置并发和资源上限。
3. 增加 Prometheus 指标：按引擎成功率、P50/P95、页/秒、降级率、队列深度和模型加载耗时。
4. 锁定 Docling、marker-pdf、PyTorch、Transformers 版本和模型校验和，建立可复现镜像。
5. MinIO 对象名加入 `fileId` 或版本号；当前同日同名上传存在覆盖风险。
6. 为解析、清洗、分块、向量化建立统一 traceId，串联 Java、DocForge、MinIO、MySQL 和 PostgreSQL 日志。

