package com.example.myllm.service;

import com.example.myllm.config.GraphProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 应用启动后的 Neo4j 健康和 Schema 基线检查。 */
@Component
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
public class Neo4jBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Neo4jBootstrap.class);

    private final Neo4jAvailabilityService availabilityService;
    private final Neo4jSchemaService schemaService;
    private final GraphProperties properties;

    public Neo4jBootstrap(
            Neo4jAvailabilityService availabilityService,
            Neo4jSchemaService schemaService,
            GraphProperties properties) {
        this.availabilityService = availabilityService;
        this.schemaService = schemaService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isHealthCheckOnStartup()) {
            return;
        }
        Neo4jAvailabilityService.Availability availability = availabilityService.check();
        if (!availability.available()) {
            if (properties.isFailFast()) {
                throw new IllegalStateException("Neo4j 启动检查失败: " + availability.error());
            }
            log.warn("Neo4j 启动检查失败，图功能暂时降级，不影响 Dense/BM25 error={}", availability.error());
            return;
        }
        log.info("Neo4j 连接成功 version={} edition={} database={}",
                availability.version(), availability.edition(), properties.getDatabase());
        if (properties.getIndexing().isEnabled()) {
            try {
                schemaService.ensureSchema();
            } catch (Exception e) {
                if (properties.isFailFast()) {
                    throw e;
                }
                log.warn("Neo4j Schema 初始化失败，构图任务将保留并重试 error={}", e.getMessage());
            }
        }
    }
}
