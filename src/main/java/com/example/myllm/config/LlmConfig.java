package com.example.myllm.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LlmConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
    ChatClient ollamaChatClient(OllamaChatModel chatModel) {
        return buildChatClient(chatModel);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "llm.provider", havingValue = "openai-compatible")
    ChatClient openAiCompatibleChatClient(OpenAiChatModel chatModel) {
        return buildChatClient(chatModel);
    }

    private static ChatClient buildChatClient(ChatModel chatModel) {
        // DefaultChatClient 会在每次请求的 advisor chain 末尾自动追加
        // ChatModelCallAdvisor。这里再次注册会形成重复的模型终结 advisor。
        return ChatClient.builder(chatModel).build();
    }
}
