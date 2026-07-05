package com.example.myllm.service;

import com.example.myllm.config.GraphProperties;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Neo4j 连接、认证和目标数据库可用性检查。
 *
 * <p>Driver 的创建是惰性的，只有显式执行检查或查询时才真正建立连接。</p>
 */
@Service
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
public class Neo4jAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(Neo4jAvailabilityService.class);

    private final Driver driver;
    private final GraphProperties properties;

    public Neo4jAvailabilityService(Driver driver, GraphProperties properties) {
        this.driver = driver;
        this.properties = properties;
    }

    /**
     * 验证 Bolt、认证和目标数据库，并读取服务版本；任何错误转换为状态，不泄露凭据。
     */
    public Availability check() {
        try {
            driver.verifyConnectivity();
            try (var session = driver.session(sessionConfig())) {
                var component = session.run(
                                "CALL dbms.components() YIELD versions, edition "
                                        + "RETURN versions[0] AS version, edition LIMIT 1")
                        .single();
                return new Availability(
                        true,
                        component.get("version").asString("unknown"),
                        component.get("edition").asString("unknown"),
                        null);
            }
        } catch (Exception e) {
            String error = e.getClass().getSimpleName()
                    + (e.getMessage() == null || e.getMessage().isBlank() ? "" : ":" + e.getMessage());
            log.warn("Neo4j 当前不可用 database={} error={}", database(), error);
            return new Availability(false, null, null, abbreviate(error, 500));
        }
    }

    /** 创建显式指定目标数据库的 Session 配置。 */
    public SessionConfig sessionConfig() {
        return SessionConfig.forDatabase(database());
    }

    private String database() {
        String configured = properties.getDatabase();
        return configured == null || configured.isBlank() ? "neo4j" : configured.trim();
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Neo4j 运行状态快照。 */
    public record Availability(boolean available, String version, String edition, String error) {
    }
}
