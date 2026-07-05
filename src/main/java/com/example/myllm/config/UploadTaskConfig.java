package com.example.myllm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(UploadTaskProperties.class)
public class UploadTaskConfig {
}
