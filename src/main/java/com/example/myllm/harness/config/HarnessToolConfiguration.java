package com.example.myllm.harness.config;

import com.example.myllm.harness.application.HarnessDefinitionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HarnessToolConfiguration {

    @Bean
    HarnessDefinitionRegistry harnessDefinitionRegistry() {
        return new HarnessDefinitionRegistry();
    }
}
