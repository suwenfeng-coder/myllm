package com.example.myllm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class VectorDataSourceConfig {

    @Bean(name = "vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate(
            @Value("${vector.datasource.url}") String url,
            @Value("${vector.datasource.username}") String username,
            @Value("${vector.datasource.password}") String password,
            @Value("${vector.datasource.driver-class-name:org.postgresql.Driver}") String driverClassName) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(driverClassName);
        return new JdbcTemplate(dataSource);
    }
}
