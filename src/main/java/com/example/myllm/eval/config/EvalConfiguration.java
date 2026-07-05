package com.example.myllm.eval.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EvalProperties.class)
public class EvalConfiguration {
}
