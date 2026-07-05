package com.example.myllm.service;

import com.example.myllm.config.GraphProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 幂等初始化 Neo4j 结构图约束和索引。
 *
 * <p>Schema 名称和 Cypher 均固定在代码内，避免将外部配置拼接进查询结构。</p>
 */
@Service
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
public class Neo4jSchemaService {

    private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaService.class);
    private static final List<String> SCHEMA_QUERIES = List.of(
            "CREATE CONSTRAINT document_file_id_unique IF NOT EXISTS "
                    + "FOR (d:Document) REQUIRE d.fileId IS UNIQUE",
            "CREATE CONSTRAINT section_id_unique IF NOT EXISTS "
                    + "FOR (s:Section) REQUIRE s.sectionId IS UNIQUE",
            "CREATE CONSTRAINT chunk_id_unique IF NOT EXISTS "
                    + "FOR (c:Chunk) REQUIRE c.chunkId IS UNIQUE",
            "CREATE CONSTRAINT entity_key_unique IF NOT EXISTS "
                    + "FOR (e:Entity) REQUIRE e.entityKey IS UNIQUE",
            "CREATE INDEX chunk_file_id_index IF NOT EXISTS FOR (c:Chunk) ON (c.fileId)",
            "CREATE INDEX entity_type_index IF NOT EXISTS FOR (e:Entity) ON (e.entityType)",
            "CREATE FULLTEXT INDEX entity_name_fulltext IF NOT EXISTS "
                    + "FOR (e:Entity) ON EACH [e.name, e.normalizedName, e.aliases]");

    private final Driver driver;
    private final Neo4jAvailabilityService availabilityService;
    private final GraphProperties properties;
    private final AtomicBoolean initialized = new AtomicBoolean();

    public Neo4jSchemaService(
            Driver driver,
            Neo4jAvailabilityService availabilityService,
            GraphProperties properties) {
        this.driver = driver;
        this.availabilityService = availabilityService;
        this.properties = properties;
    }

    /**
     * 每个应用进程最多成功初始化一次；失败不置位，待 Neo4j 恢复后由任务再次尝试。
     */
    public synchronized void ensureSchema() {
        if (initialized.get() || !properties.getIndexing().isSchemaInitializationEnabled()) {
            return;
        }
        try (var session = driver.session(availabilityService.sessionConfig())) {
            session.executeWriteWithoutResult(tx -> {
                for (String query : SCHEMA_QUERIES) {
                    tx.run(query).consume();
                }
            });
            session.run(
                            "CALL db.awaitIndexes($timeoutSeconds)",
                            Values.parameters(
                                    "timeoutSeconds", properties.getIndexing().getSchemaAwaitSeconds()))
                    .consume();
            initialized.set(true);
            log.info("Neo4j 图 Schema 初始化完成 constraints=4 indexes=3");
        }
    }

    /** 仅供测试或显式维护流程清除进程内初始化标记，不删除数据库 Schema。 */
    void resetInitializationFlag() {
        initialized.set(false);
    }
}
