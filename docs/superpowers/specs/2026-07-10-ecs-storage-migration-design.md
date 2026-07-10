# ECS 数据库与对象存储迁移设计

## 1. 目标与范围

将 `myllm` 当前运行在本机的 MySQL、ParadeDB/PostgreSQL 和 MinIO 迁移到同一台阿里云 ECS，使本地 Spring Boot 应用通过加密网络访问远端存储。迁移面向个人、低并发学习环境，优先控制成本、保证可恢复性，并保留清晰的本地回滚路径。

以下组件不迁移：

- Neo4j 及其数据继续保留本地，默认地址为 `bolt://127.0.0.1:7687`。
- Spring Boot、Ollama 和 DocForge 继续在本地运行。
- 不建设主从、集群、跨可用区高可用或自动故障转移。

## 2. 方案选择

采用单台 4GB ECS 上的 Docker Compose 三服务方案：MySQL 8、当前项目已固定镜像摘要的 ParadeDB/PostgreSQL、MinIO。三个服务使用独立持久化目录、独立健康检查和显式内存限制。

不采用阿里云 RDS/OSS，原因是本阶段为低并发学习环境，目标是控制持续成本并保留完整的数据库与对象存储实验能力。不直接开放数据库公网端口，避免把安全性建立在弱密码或单一安全组规则上。

## 3. 目标架构

```text
本地电脑
├── myllm Spring Boot :8080
├── Ollama :11434
├── DocForge :8000
└── Neo4j :7687
        │
        │ Tailscale 私网（首选）或 SSH 隧道（备选）
        ▼
阿里云 ECS 4GB
├── MySQL 8
├── ParadeDB/PostgreSQL
└── MinIO
        │
        ▼
    ECS 独立数据盘
    ├── /srv/myllm/mysql
    ├── /srv/myllm/paradedb
    ├── /srv/myllm/minio
    └── /srv/myllm/backups
```

应用数据职责保持不变：

| 组件 | 数据职责 |
|---|---|
| MySQL | JPA 业务实体、日志、上传任务、评测、Harness 状态 |
| ParadeDB/PostgreSQL | 文档分块、1024 维向量、HNSW 索引和 `pg_search` BM25 索引 |
| MinIO | 上传原文件、解析 Markdown 和大型 Artifact |
| 本地 Neo4j | 可选派生图索引和图检索 Shadow 数据 |

## 4. ECS 资源与运行约束

建议 ECS 至少为 2 vCPU、4GB RAM，并使用独立 SSD 云盘。内存预算为：

| 使用方 | 目标预算 |
|---|---:|
| 系统、Docker 与守护进程 | 700–900MB |
| MySQL | 700–900MB |
| ParadeDB/PostgreSQL | 1.2–1.5GB |
| MinIO | 400–600MB |
| 文件缓存与安全余量 | 500–800MB |

ECS 配置 2GB swap 作为瞬时峰值保护，同时通过监控保证稳定状态不持续使用 swap。批量 embedding 入库、BM25 重建等高峰任务串行执行，不并发运行大规模维护作业。

Compose 必须设置容器内存上限和日志轮转。MySQL 限制 InnoDB Buffer Pool 与连接数；PostgreSQL 限制 `shared_buffers`、`work_mem`、连接数和维护内存；MinIO限制容器内存并避免不必要的高并发扫描。具体值在实施时通过空载和一次真实批量入库验证后微调，但总容器上限不得覆盖系统安全余量。

## 5. 网络与安全

首选 Tailscale：本地和 ECS 加入同一个 tailnet，MySQL、PostgreSQL、MinIO仅绑定 ECS 的 Tailscale 地址或由主机防火墙限制为 Tailscale 网卡来源。阿里云安全组不向公网开放 `3306`、`5432`、`9000` 和 `9001`。

SSH 隧道作为备选：本地分别映射为 `127.0.0.1:13306`、`127.0.0.1:15434`、`127.0.0.1:19000`，避免与仍保留的本地容器端口冲突。隧道断开时应用应明确报连接失败，不自动回退到旧库，以避免双写或写入错误环境。

安全要求：

- MySQL、PostgreSQL 和 MinIO分别使用随机强密码，不复用当前示例密码。
- ECS 密钥和运行时 `.env` 不提交 Git；仓库仅保留无秘密的 `.env.example`。
- MinIO Console `9001` 默认不对公网开放，只经私网或 SSH访问。
- 数据盘目录仅授予对应容器运行用户所需权限。
- MySQL和PostgreSQL只创建应用所需数据库与账号，不让应用使用管理员账号。

## 6. 仓库改造边界

实施阶段预计新增或修改以下职责明确的文件：

| 文件 | 职责 |
|---|---|
| `deploy/ecs/docker-compose.yml` | ECS 三服务、数据目录、健康检查、内存与日志限制 |
| `deploy/ecs/.env.example` | ECS 端变量名和无秘密示例 |
| `deploy/ecs/mysql/conf.d/myllm.cnf` | 4GB 主机下的 MySQL 资源限制 |
| `deploy/ecs/postgres/postgresql.conf` | ParadeDB/PostgreSQL 资源限制 |
| `scripts/migration/export-local-storage.sh` | 只读导出三类本地数据，输出清单和校验值 |
| `scripts/migration/import-ecs-storage.sh` | 在空远端服务中恢复导出物 |
| `scripts/migration/verify-storage-migration.sh` | 比较关键表、记录、扩展、索引、对象数量与抽样哈希 |
| `scripts/ecs-storage-health.sh` | 从本地检查远端三服务连通性和基础读操作 |
| `src/main/resources/application.yml` | 将向量库和 MinIO默认值改为环境变量驱动，保留本地开发默认值 |
| `src/main/resources/application-local.yml.example` | 展示远端连接变量，Neo4j明确保持本地 |
| `scripts/start-all.sh`、`scripts/appctl.sh` | 区分本地 Neo4j与远端存储，不再隐式创建远端服务 |
| `scripts/README.md`、`README.md` | 记录远端存储模式、启动和故障排查 |

现有 `docker-compose.paradedb.yml` 继续承担纯本地开发环境，不改造成 ECS 文件，避免本地开发和服务器部署参数互相污染。

## 7. 配置设计

应用通过环境变量选择远端存储：

```text
MYSQL_URL
MYSQL_USERNAME
MYSQL_PASSWORD
VECTOR_DATABASE_URL
VECTOR_DATABASE_USERNAME
VECTOR_DATABASE_PASSWORD
MINIO_ENDPOINT
MINIO_ACCESS_KEY
MINIO_SECRET_KEY
NEO4J_URI=bolt://127.0.0.1:7687
```

`application.yml` 不包含真实凭据。MySQL JDBC URL保留 Unicode 和 `Asia/Shanghai` 时区参数；PostgreSQL URL指向远端 ParadeDB 的 `myllm` 数据库；MinIO endpoint 使用 Tailscale 地址或本地 SSH 映射地址。Neo4j配置不随远端存储模式变化。

## 8. 数据迁移流程

迁移遵循“准备、全量导出、恢复、只读校验、短暂停写、增量重导或最终全量、切换、观察”的顺序。由于当前是低并发学习环境，优先选择可审计的短暂停写最终全量迁移，而不引入双写或 CDC。

### 8.1 准备

1. 创建 ECS 数据盘目录和备份目录。
2. 部署空的三服务并确认版本、扩展、健康状态和磁盘余量。
3. 记录本地镜像版本、数据库版本、数据库名、账号、卷名和对象桶。
4. 检查导出空间至少为当前有效数据量的两倍。

### 8.2 MySQL

停止本地应用写入后，用一致性快照方式导出 `myllm` 库，包含结构、数据、触发器和事件。恢复到远端空库后校验表集合、逐表记录数、关键状态分布和抽样数据。应用账号仅获得该库所需权限。

### 8.3 ParadeDB/PostgreSQL

先在远端确认项目依赖的 `vector`、`pg_search` 等扩展与本地兼容，再用 PostgreSQL 自定义格式导出并恢复。恢复后检查扩展、表、主键、HNSW/BM25 索引、记录数和向量维度。执行一组固定 Dense、BM25 和混合检索查询，对比结果是否可接受；索引若因版本限制无法直接恢复，应在恢复数据后显式重建并记录耗时。

### 8.4 MinIO

使用 MinIO Client 对 `myllm` 桶做递归镜像，保留对象路径和元数据。首次可在应用仍运行时预同步，停写后再执行最终同步。校验对象总数、总字节数，并对全部小对象和抽样大对象比较哈希。迁移脚本不得删除源端对象，也不得使用会删除目标端额外对象的镜像参数。

## 9. 验收标准

基础验收：

- ECS重启后三个服务自动恢复，健康检查通过。
- ECS空闲时无持续 swap，磁盘使用率低于 70%。
- 数据库端口和 MinIO端口无法从公网直接访问。
- 本地 Neo4j仍可独立启动、停止和查询。

数据验收：

- MySQL表集合一致，逐表记录数一致，关键任务和 Harness 状态可读取。
- PostgreSQL扩展、表和索引齐全，`file_embeddings` 记录数一致，向量维度为1024。
- 固定检索样例的 Dense、BM25和融合查询正常，无静默降级。
- MinIO对象数与总字节数一致，抽样哈希一致。

业务验收：

- Spring Boot正常启动，启动日志显示连接到预期远端主机。
- 文件列表、已有文档问答和引用返回正常。
- 上传一个小文件后，MySQL任务完成、PostgreSQL产生分块、MinIO产生原文件和解析结果。
- 删除该测试文件时，跨存储清理结果符合现有补偿逻辑。
- 开启本地图功能时，Neo4j索引和 Shadow检索不受影响。

## 10. 回滚

切换前记录本地配置和三个本地数据源的只读快照。切换后至少7天不删除本地容器、卷或导出包。

若验收失败：

1. 停止本地 Spring Boot，阻止继续写远端。
2. 恢复迁移前的本地连接配置。
3. 启动并检查本地 MySQL、ParadeDB和MinIO。
4. 启动应用并执行最小业务验证。
5. 将切换后远端新增数据单独导出保存，再分析失败原因；不得覆盖原始迁移包。

本方案不实现自动双向回滚。切换后的新增写入不会自动合并回本地，因此首次观察期应避免批量导入重要新数据。

## 11. 备份与运维

- MySQL每日逻辑备份，保留7个日备份和4个周备份。
- PostgreSQL每日自定义格式逻辑备份；在批量入库或索引重建前额外备份。
- MinIO每日生成对象清单，并将关键桶定期同步到独立位置；同一块数据盘内的副本不算灾备。
- 每日检查备份文件非空，每周至少执行一次自动可读性检查，每月做一次临时目录恢复演练。
- 告警阈值：数据盘使用率70%预警、85%严重；可用内存低于400MB持续5分钟预警；出现OOM、容器反复重启或持续swap立即停止批量任务。

## 12. 实施阶段划分

1. 仓库配置和ECS部署资产。
2. ECS空服务部署及安全加固。
3. 本地数据盘点与首次演练迁移。
4. 停写后的正式迁移和校验。
5. 应用切换与端到端验收。
6. 备份、监控、恢复演练和文档收尾。

每一阶段都必须有独立校验结果；前一阶段未通过时不得进入正式切换。
