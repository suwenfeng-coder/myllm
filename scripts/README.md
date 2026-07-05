# Scripts

## 单个服务控制（推荐）

统一使用：

```bash
/bin/bash ./scripts/appctl.sh <start|stop|restart|status|logs> <service>
```

当前 macOS 会给工作区脚本附加 `com.apple.provenance`，直接执行 `./scripts/*.sh` 可能被系统拦截；显式使用 `/bin/bash` 可稳定绕过。频繁操作推荐使用下方 Make 命令。

最短命令：

```bash
make restart-java
make restart-python
make status-java
make status-python
```

Java 项目：

```bash
/bin/bash ./scripts/appctl.sh start myllm
/bin/bash ./scripts/appctl.sh stop myllm
/bin/bash ./scripts/appctl.sh restart myllm
/bin/bash ./scripts/appctl.sh status myllm
/bin/bash ./scripts/appctl.sh logs myllm
```

Python DocForge：

```bash
/bin/bash ./scripts/appctl.sh start docforge
/bin/bash ./scripts/appctl.sh stop docforge
/bin/bash ./scripts/appctl.sh restart docforge
/bin/bash ./scripts/appctl.sh status docforge
/bin/bash ./scripts/appctl.sh logs docforge
```

支持 `java`/`app` 作为 `myllm` 别名，支持 `python` 作为 `docforge` 别名：

```bash
/bin/bash ./scripts/appctl.sh restart java
/bin/bash ./scripts/appctl.sh restart python
```

基础设施也可以单独操作：

```bash
/bin/bash ./scripts/appctl.sh restart postgres
/bin/bash ./scripts/appctl.sh restart minio
/bin/bash ./scripts/appctl.sh restart neo4j
/bin/bash ./scripts/appctl.sh status all
```

单独启动应用不会自动重启其他依赖。例如 `restart myllm` 只操作 8080 Java 进程，不会改动 DocForge 或任何 Docker 容器。

## 一键启动

```bash
cd /Users/suwenfeng/Documents/code/myllm
/bin/bash ./scripts/start-all.sh
```

等价于：

```bash
/bin/bash ./scripts/appctl.sh start all
```

启动项：
- Docker Desktop（若未启动）
- `rag_hybrid`（pgvector 数据库）
- `minio-server`
- `myllm-neo4j`（存在该容器时启动，不自动创建或重置密码）
- DocForge 服务（`python/docforge`）
  - 支持 `docling` 与 `maker`；页面推荐使用 `auto` 智能路由
- myllm 应用（Spring Boot）

## 一键停止

```bash
cd /Users/suwenfeng/Documents/code/myllm
/bin/bash ./scripts/stop-all.sh
```

等价于：

```bash
/bin/bash ./scripts/appctl.sh stop all
```

停止项：
- myllm（`spring-boot:run`）
- DocForge（`uvicorn app.main:app`）
- `rag_hybrid`、`minio-server` 与 `myllm-neo4j` 容器

## 一键重启

```bash
cd /Users/suwenfeng/Documents/code/myllm
/bin/bash ./scripts/restart-all.sh
```

等价于：

```bash
/bin/bash ./scripts/appctl.sh restart all
```

## 状态检查

```bash
cd /Users/suwenfeng/Documents/code/myllm
/bin/bash ./scripts/check-services.sh
```

## 日志

`start-all.sh` 会把启动日志写到：
- `.runtime/myllm.log`
- `.runtime/docforge.log`

## 手动执行建议顺序

如果你希望手动一步步执行，建议按这个顺序：

1. `/bin/bash ./scripts/stop-all.sh`
2. `/bin/bash ./scripts/start-all.sh`
3. `/bin/bash ./scripts/check-services.sh`

若 `myllm` 仍不可访问，请直接看：

```bash
tail -n 120 .runtime/myllm.log
```

## Neo4j 图索引

本地容器固定使用 Neo4j 5.26.x，连接地址为 `bolt://127.0.0.1:7687`。启动图索引前设置：

```bash
export NEO4J_PASSWORD='本地Neo4j密码'
export GRAPH_ENABLED=true
export GRAPH_INDEXING_ENABLED=true
```

图在线召回尚处于后续 Shadow 评测阶段，保持：

```bash
export GRAPH_RETRIEVAL_ENABLED=false
```

运行应用后可以检查：

```bash
curl http://127.0.0.1:8080/api/graph/health
curl http://127.0.0.1:8080/api/files/{fileId}/graph-status
```

为已有 pgvector 文件分批创建构图任务（不会在请求线程同步构图）：

```bash
curl -X POST 'http://127.0.0.1:8080/api/graph/backfill?limit=100'
```

## 后续自动扩展启动项（推荐）

为了避免每次新增服务都改 3 个脚本，已内置扩展钩子：

1. 复制模板：

```bash
cd /Users/suwenfeng/Documents/code/myllm
cp scripts/services.local.example.sh scripts/services.local.sh
```

2. 在 `scripts/services.local.sh` 中维护：
- `start_custom_services`
- `stop_custom_services`
- `print_custom_summary`（可选）

之后你只改这一个文件，`start-all.sh` / `stop-all.sh` / `restart-all.sh` 会自动生效。
