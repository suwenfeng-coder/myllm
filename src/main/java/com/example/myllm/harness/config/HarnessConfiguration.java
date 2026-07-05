package com.example.myllm.harness.config;

import com.example.myllm.harness.domain.HarnessActionParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HarnessProperties.class)
public class HarnessConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HarnessActionParser harnessActionParser(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new HarnessActionParser(objectMapper);
    }
}
