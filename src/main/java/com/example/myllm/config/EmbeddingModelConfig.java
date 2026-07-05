package com.example.myllm.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class EmbeddingModelConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
    EmbeddingModel primaryOllamaEmbeddingModel(
            @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel) {
        return ollamaEmbeddingModel;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "llm.provider", havingValue = "openai-compatible")
    EmbeddingModel primaryOpenAiEmbeddingModel(
            @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        return openAiEmbeddingModel;
    }
}
