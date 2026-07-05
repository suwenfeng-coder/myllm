# Neo4j 集成详细配置与开发计划

> 制定日期：2026-07-03  
> 项目：myllm  
> 本地 Neo4j：Docker 容器 `myllm-neo4j`，Neo4j `5.26.28`  
> 连接端口：Browser `127.0.0.1:7474`，Bolt `127.0.0.1:7687`

## 一、集成目标与边界

Neo4j 作为现有 RAG 的“知识图谱索引与图召回通道”，不替代 PostgreSQL、pgvector、BM25、MySQL 或 MinIO。

各存储职责固定如下：

| 存储 | 数据职责 | 是否为正文主数据 |
| --- | --- | --- |
| PostgreSQL / pgvector | Chunk 正文、Embedding、BM25 字段、检索元数据 | 是 |
| Neo4j | 文档结构、章节、Chunk 引用、实体、关系和证据链 | 否 |
| MySQL | 文件处理、构图任务、RAG 调用和失败审计 | 否 |
| MinIO | 原始文件和解析结果 | 是 |

Neo4j 节点只保存稳定标识、检索属性和短预览，不重复保存完整 Chunk，也不在第一阶段重复保存向量。图召回返回 `fileId + chunkIndex`，再从 PostgreSQL 批量读取最新正文。

目标链路：

```text
文档上传
  -> DocForge/Local 解析
  -> 数据清洗
  -> 文档分片
  -> pgvector/BM25 入库成功
  -> MySQL 创建构图任务
  -> Neo4j 写入结构图
  -> 可选实体和关系抽取

用户查询
  -> 问题改写
  -> Dense 召回
  -> BM25 召回
  -> Graph 召回
  -> 多路加权 RRF
  -> 去重、文件范围限制、单文档限额
  -> 可选 Reranker
  -> Prompt 组装
```

## 二、关键技术决策

### 2.1 使用官方 Java Driver

第一阶段使用 `neo4j-java-driver` 和显式 Cypher，不使用 Spring Data Neo4j 的 `@Node` 与 `Neo4jRepository`。

原因：

1. 当前主要需求是批量 `UNWIND`、`MERGE` 和多跳查询，显式 Cypher更直接。
2. 项目已经启用 Spring Data JPA，避免 JPA Entity 与 Neo4j Node 的扫描边界复杂化。
3. 图模型是 PostgreSQL Chunk 的派生索引，不需要完整的对象图映射。
4. 便于控制查询超时、数据库名、批次大小和降级行为。

### 2.2 使用最终一致性

不尝试在 MySQL、PostgreSQL、MinIO 和 Neo4j 之间建立分布式事务。

向量入库全部成功后，在 MySQL 创建持久化构图任务；后台任务幂等写入 Neo4j。Neo4j 不可用时，文件向量化仍可成功，任务保持 `PENDING/FAILED` 并允许重试。

### 2.3 分阶段构图

1. **结构图**：Document、Section、Chunk、HAS_CHUNK、NEXT。完全基于当前分片元数据，不调用 LLM。
2. **实体图**：Entity、MENTIONS。需要受控实体抽取。
3. **关系图**：RELATED_TO 等白名单关系。每条关系必须保留证据 Chunk。
4. **Graph RAG**：先灰度只记录召回结果，再参与融合排序。

## 三、Docker 与 Neo4j 运行配置

### 3.1 固定镜像版本

当前容器虽由 `neo4j:5` 创建，但实际版本为 `5.26.28`。开发、集成测试和生产应固定为：

```text
neo4j:5.26.28
```

避免浮动标签升级后出现 Cypher、插件或行为变化。后续重建容器前必须确认卷已备份；本计划不执行容器或数据卷删除。

### 3.2 当前连接参数

```text
Container: myllm-neo4j
HTTP:      http://127.0.0.1:7474
Bolt:      bolt://127.0.0.1:7687
Database:  neo4j（开发阶段默认）
Username:  neo4j
```

本机端口只绑定到 `127.0.0.1`，保持现状，不改为 `0.0.0.0`。

### 3.3 启停与检查

```bash
docker start myllm-neo4j
docker stop myllm-neo4j
docker ps --filter name=myllm-neo4j
docker logs --tail 100 myllm-neo4j
docker exec myllm-neo4j neo4j version
```

密码不要直接写进命令历史。连接验证优先使用 Browser，或通过临时环境变量调用 `cypher-shell`。

### 3.4 开发机资源建议

本机还运行 Ollama、DocForge、MySQL、PostgreSQL 和 MinIO，Neo4j 初始资源建议：

```text
Heap initial: 1 GiB
Heap max:     1 GiB
Page cache:   1 GiB
```

数据规模稳定后再依据 Neo4j memory recommendation、查询 P95 和 page cache 命中情况调整。开发阶段不安装 APOC；首版方案不依赖插件。

## 四、Spring Boot 配置

### 4.1 Maven 依赖

在 `pom.xml` 增加官方驱动，版本交给 Spring Boot `3.4.1` 的依赖管理：

```xml
<dependency>
    <groupId>org.neo4j.driver</groupId>
    <artifactId>neo4j-java-driver</artifactId>
</dependency>
```

集成测试阶段增加：

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>neo4j</artifactId>
    <scope>test</scope>
</dependency>
```

### 4.2 应用配置

`application.yml` 中只放非敏感默认值，并默认关闭图能力：

```yaml
spring:
  neo4j:
    uri: ${NEO4J_URI:bolt://127.0.0.1:7687}
    authentication:
      username: ${NEO4J_USERNAME:neo4j}
      password: ${NEO4J_PASSWORD:}

graph:
  enabled: ${GRAPH_ENABLED:false}
  database: ${NEO4J_DATABASE:neo4j}
  health-check-on-startup: true
  fail-fast: false

  indexing:
    enabled: ${GRAPH_INDEXING_ENABLED:false}
    batch-size: 200
    task-poll-interval-ms: 5000
    max-attempts: 5
    extraction-version: structure-v1
    entity-extraction-enabled: false
    entity-extraction-version: entity-v1
    min-confidence: 0.75

  retrieval:
    enabled: ${GRAPH_RETRIEVAL_ENABLED:false}
    shadow-mode: true
    seed-top-k: 10
    candidate-top-k: 30
    max-hops: 2
    timeout-ms: 3000
    min-score: 0.20
    rrf-weight: 0.80
    fulltext-index-name: entity_name_fulltext
```

`application-local.yml.example` 增加示例，但不提交真实密码：

```yaml
spring:
  neo4j:
    uri: bolt://127.0.0.1:7687
    authentication:
      username: neo4j
      password: ${NEO4J_PASSWORD}

graph:
  enabled: true
  database: neo4j
  indexing:
    enabled: true
  retrieval:
    enabled: false
    shadow-mode: true
```

本地启动前设置：

```bash
export NEO4J_PASSWORD='本地Neo4j密码'
export GRAPH_ENABLED=true
export GRAPH_INDEXING_ENABLED=true
```

### 4.3 启动和降级策略

- `graph.enabled=false`：不创建任何图服务任务，现有行为完全不变。
- `graph.enabled=true` 且 Neo4j 不可用：应用继续启动，记录健康告警。
- `graph.indexing.enabled=false`：不消费构图任务。
- `graph.retrieval.enabled=false`：查询只使用现有 Dense + BM25。
- `shadow-mode=true`：执行图召回并记录指标，但不改变最终候选排序。
- 只有完成评测后才将 `shadow-mode` 调为 `false`。

## 五、代码结构计划

建议新增：

```text
src/main/java/com/example/myllm/
  config/
    GraphProperties.java
    Neo4jIntegrationConfig.java

  support/graph/
    GraphDocument.java
    GraphSection.java
    GraphChunk.java
    GraphEntity.java
    GraphRelation.java
    GraphExtractionResult.java
    GraphRetrievalHit.java

  service/
    Neo4jAvailabilityService.java
    Neo4jSchemaService.java
    GraphIndexTaskService.java
    GraphIndexingService.java
    GraphKnowledgeExtractor.java
    StructureGraphExtractor.java
    LlmGraphKnowledgeExtractor.java
    GraphRetrievalService.java

  entity/
    DocumentGraphIndexTask.java

  repository/
    DocumentGraphIndexTaskRepository.java
```

职责划分：

| 类 | 职责 |
| --- | --- |
| `GraphProperties` | 绑定 `graph.*` 配置并做范围校验 |
| `Neo4jAvailabilityService` | `verifyConnectivity()`、版本与数据库检查、状态缓存 |
| `Neo4jSchemaService` | 幂等创建约束和索引，等待索引上线 |
| `GraphIndexTaskService` | 创建、领取、重试和完成 MySQL 构图任务 |
| `StructureGraphExtractor` | 从文件、标题和 Chunk 生成确定性结构图 |
| `LlmGraphKnowledgeExtractor` | 使用结构化 JSON 抽取实体和关系 |
| `GraphIndexingService` | 批量 `UNWIND + MERGE` 写入和按文件替换派生图 |
| `GraphRetrievalService` | 种子实体检索、有限跳扩展、返回 Chunk 引用 |

所有新增公共类和方法必须增加中文注释，说明幂等、事务和降级语义。

## 六、Neo4j 图模型

### 6.1 节点

#### Document

```text
fileId            全局唯一，沿用 PostgreSQL file_id
fileName
contentType
parserEngine
cleanerVersion
chunkStrategy
createdAt
updatedAt
```

#### Section

```text
sectionId         fileId + headingPathHash
fileId
headingPath
level
```

#### Chunk

```text
chunkId           fileId + ':' + chunkIndex
fileId
chunkIndex
contentHash
headingPath
charCount
tokenCount
preview           最多约 300 字符，不保存完整正文
```

#### Entity

```text
entityKey         entityType + ':' + normalizedName
name
normalizedName
entityType
aliases
```

### 6.2 关系

```text
(Document)-[:HAS_SECTION]->(Section)
(Document)-[:HAS_CHUNK]->(Chunk)
(Section)-[:HAS_CHUNK]->(Chunk)
(Chunk)-[:NEXT]->(Chunk)
(Chunk)-[:MENTIONS {confidence, count, extractionVersion}]->(Entity)
(Entity)-[:RELATED_TO {relationType, confidence, evidenceChunkIds, extractionVersion}]->(Entity)
```

`NEXT` 只能连接同一文件内相邻 Chunk。第一阶段不创建无证据关系，不允许无限跳路径查询。

### 6.3 实体类型白名单

结合当前信用卡制度、用户手册和数据字典场景，首批类型：

```text
ORGANIZATION
PRODUCT
CARD
ACCOUNT
POLICY
PROCESS
ROLE
TERM
TABLE
FIELD
SYSTEM
```

关系类型使用受控字符串属性 `relationType`，首批可支持：

```text
BELONGS_TO
APPLIES_TO
REQUIRES
PROHIBITS
DEPENDS_ON
USES
CONTAINS
MAPS_TO
PRECEDES
```

## 七、约束与索引

由 `Neo4jSchemaService` 执行固定 Cypher 列表，不接受外部传入 label、关系类型或属性名。

```cypher
CREATE CONSTRAINT document_file_id_unique IF NOT EXISTS
FOR (d:Document) REQUIRE d.fileId IS UNIQUE;

CREATE CONSTRAINT section_id_unique IF NOT EXISTS
FOR (s:Section) REQUIRE s.sectionId IS UNIQUE;

CREATE CONSTRAINT chunk_id_unique IF NOT EXISTS
FOR (c:Chunk) REQUIRE c.chunkId IS UNIQUE;

CREATE CONSTRAINT entity_key_unique IF NOT EXISTS
FOR (e:Entity) REQUIRE e.entityKey IS UNIQUE;

CREATE INDEX chunk_file_id_index IF NOT EXISTS
FOR (c:Chunk) ON (c.fileId);

CREATE INDEX entity_type_index IF NOT EXISTS
FOR (e:Entity) ON (e.entityType);

CREATE FULLTEXT INDEX entity_name_fulltext IF NOT EXISTS
FOR (e:Entity) ON EACH [e.name, e.normalizedName, e.aliases];
```

启动时创建索引必须可配置。生产环境建议在迁移阶段显式执行并确认 `SHOW INDEXES` 状态为 `ONLINE`，不在每个查询请求中检查或创建索引。

## 八、构图任务和一致性

### 8.1 MySQL 任务表

新增迁移 `src/main/resources/db/document_graph_index_task.sql`：

```sql
CREATE TABLE IF NOT EXISTS document_graph_index_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    status VARCHAR(24) NOT NULL,
    extraction_version VARCHAR(64) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    node_count INT NOT NULL DEFAULT 0,
    relationship_count INT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    next_retry_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_graph_task_file_version (file_id, extraction_version),
    KEY idx_graph_task_status_retry (status, next_retry_at)
);
```

状态：

```text
PENDING -> RUNNING -> SUCCESS
                  -> FAILED -> PENDING（重试）
                  -> DEAD（达到最大次数）
```

### 8.2 接入 FileEmbeddingService

现有 `FileEmbeddingService.embedAndStore()` 在所有 Chunk 成功写入 PostgreSQL 后：

1. 创建或更新 `PENDING` 构图任务。
2. 不在 HTTP 上传线程同步调用 Neo4j。
3. 构图任务只记录 `fileId`，处理时从 PostgreSQL重新读取 Chunk。
4. 同一 `fileId + extractionVersion` 保证幂等。

如果现有向量入库出现部分成功，不能创建构图任务。长期应先完成向量版本化和原子激活，再让图索引只读取 `ACTIVE` 版本。

### 8.3 Neo4j 批量写入

每批默认 200 个 Chunk，使用参数化 Cypher：

```cypher
UNWIND $chunks AS row
MERGE (c:Chunk {chunkId: row.chunkId})
SET c.fileId = row.fileId,
    c.chunkIndex = row.chunkIndex,
    c.contentHash = row.contentHash,
    c.headingPath = row.headingPath,
    c.preview = row.preview,
    c.updatedAt = datetime()
```

禁止拼接用户文本到 Cypher；只有代码内固定的 label 和关系类型可以进入查询结构。

### 8.4 更新和删除

- 重新构建同一文件时，以 `fileId` 为范围替换旧的 Section、Chunk 及其派生关系。
- 全局 Entity 不随单个文件直接删除。
- 清理不再被任何 Chunk 引用的 Entity 应作为单独维护任务，并设置数量上限。
- 文件删除流程必须先写删除任务，再分别处理 PostgreSQL、Neo4j 和 MinIO，记录各存储结果。
- 不执行文件系统批量删除；涉及本地文件清理时遵守一次一个明确路径的限制。

## 九、实体与关系抽取

### 9.1 第一版只启用结构图

`entity-extraction-enabled=false`。先验证结构图的幂等性、失败补偿和 Chunk 引用完整性。

### 9.2 LLM 抽取协议

后续 `LlmGraphKnowledgeExtractor` 输出固定 JSON：

```json
{
  "entities": [
    {
      "localId": "e1",
      "name": "示例实体",
      "normalizedName": "示例实体",
      "type": "PRODUCT",
      "aliases": [],
      "confidence": 0.92
    }
  ],
  "relations": [
    {
      "sourceId": "e1",
      "targetId": "e2",
      "type": "APPLIES_TO",
      "confidence": 0.86,
      "evidence": "原文中的短证据"
    }
  ]
}
```

约束：

1. JSON Schema 校验失败则整块失败，不做宽松字符串猜测。
2. 实体名称执行 Unicode NFKC、空白归一化和类型校验。
3. `confidence < 0.75` 不入图。
4. Relation 两端必须存在于当前抽取结果或已解析实体中。
5. 每条 MENTIONS/RELATED_TO 记录 `chunkId` 或 `evidenceChunkIds`。
6. Prompt、模型和抽取规则共同组成 `entity-extraction-version`。
7. 抽取任务限流，不能与用户聊天争抢全部 Ollama 推理资源。

## 十、Graph RAG 检索设计

### 10.1 图召回步骤

1. 复用 `QueryRewriteService` 的规范化问题、文件名提示和有效 `fileIds`。
2. 从问题提取实体词、字段名、制度名、产品名或型号。
3. 精确匹配 `normalizedName`，再调用 `entity_name_fulltext` 获取种子实体。
4. 从种子实体反查 `MENTIONS` Chunk。
5. 可选扩展一跳 `RELATED_TO`；配置上限为 2 跳，但首版只启用 1 跳。
6. 严格应用 `fileIds` 过滤和候选数量上限。
7. 返回 `fileId + chunkIndex + graphScore + matchedEntities + evidencePath`。
8. 在 PostgreSQL 一次批量读取对应 Chunk 正文。

禁止使用未限制深度和数量的可变路径查询。

### 10.2 多路融合改造

当前 `ReciprocalRankFusion` 只支持 Dense + BM25，并将两路理论最大值归一化。接入图召回时需要重构为 N 路加权 RRF：

```text
fusionScore = Σ channelWeight / (rrfK + rank)
```

初始权重：

```text
Dense = 1.00
BM25 = 0.90
Graph = 0.80
rrfK = 60
```

新增统一候选结构，分别保存：

```text
denseScore / denseRank
bm25Score / bm25Rank
graphScore / graphRank
fusionScore
matchedEntities
evidencePath
```

不能把 `fusionScore` 或 `graphScore` 写入 `VectorChunkResult.similarity` 后再套用余弦阈值。现有 `min-similarity` 只约束 Dense；Graph 使用独立 `min-score`，最终融合使用排名和来源证据判断。

### 10.3 Shadow 模式

第一阶段 Graph 召回结果只写日志，不参与最终 Prompt：

```text
retrievalMode = HYBRID_GRAPH_SHADOW
```

比较 Graph 与最终相关 Chunk 的重合率、独有正确命中率和错误扩展率后，再启用：

```text
retrievalMode = HYBRID_GRAPH
```

### 10.4 降级

图连接、索引或查询失败时：

```text
Dense + BM25 + Graph
          |
          +-- Graph 失败 -> Dense + BM25
          +-- BM25 失败 -> Dense + Graph
          +-- Graph/BM25 都失败 -> Dense
```

任何可选召回通道失败都不能导致聊天请求整体失败。

## 十一、日志与可观测性

`rag_call_log` 计划增加：

```text
graph_attempted
graph_available
graph_seed_count
graph_hit_count
graph_applied_count
graph_duration_ms
graph_fallback_reason
graph_extraction_version
retrieval_mode
```

日志不得记录 Neo4j 密码、完整文档或完整 Cypher 参数。图查询日志只记录 query 长度、种子数量、候选数量、耗时和错误分类。

指标：

- Neo4j 连通状态和恢复次数。
- 构图任务 PENDING/RUNNING/FAILED/DEAD 数量。
- 单文档构图节点数、关系数、耗时和重试次数。
- Graph 查询 P50/P95/P99。
- 种子实体命中率、Graph 独有正确命中率和空召回率。
- Graph 降级比例。
- 三路融合后 Recall@K、MRR 和 nDCG 变化。

## 十二、测试计划

### 12.1 单元测试

- 配置默认关闭及边界值校验。
- `chunkId/sectionId/entityKey` 的稳定生成。
- 实体规范化与白名单过滤。
- 构图任务状态转换和指数退避。
- `UNWIND` 批次拆分。
- N 路加权 RRF 排名、去重和缺失通道。
- Graph 失败后的 Dense/BM25 降级。
- Shadow 模式不改变现有最终候选。

### 12.2 Neo4j 集成测试

使用 Testcontainers 固定 `neo4j:5.26.28`：

- Schema 初始化可重复执行。
- 相同文件重复构图节点数不增长。
- 文件更新后旧 Chunk 不残留。
- fileId 范围过滤有效。
- 一跳和两跳查询不会越过候选上限。
- 特殊字符只能作为参数，不能改变 Cypher 结构。
- 容器停止后应用服务正确返回 unavailable，而不是抛出到 ChatService。

### 12.3 回归与效果测试

建立至少 100 条带标准答案的业务问题，分别运行：

```text
Dense
Dense + BM25
Dense + BM25 + Graph Shadow
Dense + BM25 + Graph
Dense + BM25 + Graph + Reranker
```

至少统计：Recall@5、Recall@10、MRR、nDCG@10、错误引用率、NO_HIT 准确率和检索 P95。

## 十三、安全、备份与运维

1. 开发环境允许使用默认 `neo4j` 用户；生产环境如版本和授权支持，应创建最小权限应用用户。
2. 密码只从环境变量或密钥管理服务读取，不进入 Git、日志或前端。
3. Bolt/HTTP 只在本地绑定 `127.0.0.1`；部署到服务器后通过防火墙限制来源。
4. 生产连接启用 TLS，并使用 `neo4j+s://` 或与证书策略匹配的 URI。
5. 固定 `5.26.28` 镜像和 Testcontainers 版本，升级前运行完整回归。
6. 定期检查 `SHOW INDEXES`、慢查询、存储体积和无引用实体。
7. 备份使用 Neo4j 官方 dump/backup 流程，恢复演练通过后才视为备份有效。
8. 不使用 Neo4j 保存原始文件或敏感完整正文。

## 十四、实施里程碑

### M0：环境基线

- [ ] 将容器浮动标签从 `neo4j:5` 固定为 `neo4j:5.26.28`（当前实际版本已确认，重建前需人工确认）。
- [x] 验证 Browser、Bolt、认证和默认数据库。
- [x] 保存当前容器启动参数和数据卷名称（`neo4j_data`、`neo4j_logs`）。
- [ ] 确认开发机内存配置和日志位置。

### M1：驱动与健康检查

- [x] 增加 Java Driver 和 `spring.neo4j.*` 配置。
- [x] 实现 `GraphProperties` 和三个功能开关。
- [x] 实现连通性检查、数据库选择和 fail-soft。
- [x] 增加配置与结构图标识单元测试；真实连接由健康接口和本地 Schema 初始化验证。

**验收**：Neo4j 正常时连通；Neo4j 停止时应用仍可启动，现有接口正常。

### M2：Schema 与结构图

- [x] 实现约束、索引初始化和 ONLINE 检查。
- [x] 增加 MySQL 构图任务表，并完成本地数据库迁移。
- [x] 实现 Document/Section/Chunk/NEXT 构图。
- [x] 使用批量参数化 Cypher和幂等 MERGE。
- [x] 接入上传完成后的任务创建。

**验收**：同一文件重复构图无重复节点；所有 Chunk 都能映射回 PostgreSQL。

### M3：任务可靠性

- [x] 实现后台领取、超时恢复、指数退避和有限重试（长任务心跳仍可按规模补充）。
- [x] 增加 FAILED/DEAD/CANCELED 错误审计。
- [x] 完成文件更新、删除和重建任务流程。
- [x] 增加构图状态查询和手工重试接口；页面展示待后续补充。

**验收**：Neo4j 中断并恢复后任务可继续，不需要重新上传文件。

### M4：实体和关系抽取

- [ ] 定义实体/关系 JSON Schema 和白名单。
- [ ] 实现结构化输出解析、规范化、置信度过滤。
- [ ] 保存抽取版本和证据 Chunk。
- [ ] 对 Ollama 抽取任务做并发隔离和限流。

**验收**：所有关系可追溯；不存在白名单外关系；重复抽取结果幂等。

### M5：Graph Shadow 召回

- [ ] 实现精确实体、全文实体和一跳关系召回。
- [ ] PostgreSQL 批量回填 Chunk 正文。
- [ ] 记录 Graph 命中、耗时和降级原因。
- [ ] Shadow 模式接入 `RagRetrievalService`。

**验收**：开启 Shadow 前后最终答案和候选完全一致，日志中可比较 Graph 收益。

### M6：三路融合与灰度

- [ ] 将双路 RRF 重构为 N 路加权 RRF。
- [ ] 分离 Dense、BM25、Graph 和 Fusion 分数。
- [ ] 基于评测集调权重、阈值和最大跳数。
- [ ] 按请求比例或配置灰度启用 Graph 候选。

**验收**：Recall@K 或 MRR 有明确提升，错误引用率不高于基线，Graph 查询 P95 达标。

### M7：Reranker 与投产

- [ ] 三路粗召回统一进入 Reranker。
- [ ] 增加超时、熔断、连接池和告警。
- [ ] 完成备份恢复演练和故障降级演练。
- [ ] 固化容量基线和上线回滚手册。

## 十五、初始验收目标

```text
Neo4j 不可用时应用启动成功率 = 100%
图索引重复执行产生的重复节点数 = 0
Chunk 到 PostgreSQL 映射完整率 = 100%
无证据关系数量 = 0
Graph 在线查询 P95 < 200ms（本地目标，按数据量复核）
完整 RAG 检索 P95 < 500ms（不含 LLM 生成）
图查询失败时降级成功率 = 100%
引入 Graph 后 Recall@10 不低于 Dense + BM25 基线
错误引用率不高于现有基线
```

## 十六、回滚方案

回滚不需要删除 Neo4j 数据：

1. 设置 `GRAPH_RETRIEVAL_ENABLED=false`，立即退出在线图召回。
2. 设置 `GRAPH_INDEXING_ENABLED=false`，暂停构图任务消费。
3. 必要时设置 `GRAPH_ENABLED=false`，完全关闭项目内 Neo4j 功能。
4. 保留 MySQL 构图任务和 Neo4j 数据用于问题分析。
5. 现有 Dense + BM25 链路继续工作。
6. 经人工确认后再决定是否逐项清理图数据、容器或卷，不执行批量删除。

## 十七、推荐实施顺序

立即实施范围建议为 **M0～M3**：先完成连接、结构图和可靠任务链路。结构图稳定并积累评测数据后，再实施 **M4～M6**。Reranker 与生产运维作为最后阶段。

首个可交付版本不应直接打开 Graph 在线融合；应以 `Graph Shadow` 作为质量闸门。
