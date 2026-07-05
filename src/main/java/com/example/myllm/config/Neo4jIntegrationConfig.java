package com.example.myllm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用项目自有的 Neo4j 图能力配置绑定。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphProperties.class)
public class Neo4jIntegrationConfig {
}
