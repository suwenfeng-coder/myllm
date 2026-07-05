package com.example.myllm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ChatServiceTests {

    @Test
    void simpleChatExecutesModelOnlyOnceWhenReadingContentAndMetrics() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("连接正常"))));
        };
        ChatService service = new ChatService(
                ChatClient.builder(model).build(),
                mock(TransactionLogService.class),
                mock(RagCallLogService.class),
                mock(FileEmbeddingService.class),
                mock(RagRetrievalService.class),
                "ollama",
                "mymodel-base",
                "unused",
                0.45);

        assertEquals("连接正常", service.simpleChat("测试"));
        assertEquals(1, calls.get());
    }
}
