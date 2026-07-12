package com.example.myllm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.myllm.support.retrieval.QueryIntent;
import com.example.myllm.support.retrieval.QueryRewriteResult;
import com.example.myllm.support.retrieval.QueryRewriteStrategy;
import com.example.myllm.testing.LogCapture;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ChatServiceTests {

    private static ChatService createService(
            ChatModel model,
            RagRetrievalService ragRetrievalService) {
        return new ChatService(
                ChatClient.builder(model).build(),
                mock(TransactionLogService.class),
                mock(RagCallLogService.class),
                mock(FileEmbeddingService.class),
                ragRetrievalService,
                "ollama",
                "mymodel-base",
                "unused",
                0.45);
    }

    private static void assertLogsExclude(LogCapture logs, String... forbiddenValues) {
        for (String message : logs.messages()) {
            assertFalse(message.contains("\r"), message);
            assertFalse(message.contains("\n"), message);
            for (String forbidden : forbiddenValues) {
                assertFalse(message.contains(forbidden), message);
            }
        }
    }

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

    @Test
    void simpleChatFailsClearlyWhenModelReturnsNullResponse() {
        ChatModel model = prompt -> null;
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

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.simpleChat("测试"));

        assertTrue(ex.getMessage().contains("模型返回空响应"));
    }

    @Test
    void simpleChatLogsOnlyTrustedFields() {
        ChatModel model = prompt ->
                new ChatResponse(List.of(new Generation(new AssistantMessage("连接正常"))));
        ChatService service = createService(model, mock(RagRetrievalService.class));

        try (LogCapture logs = LogCapture.forClass(ChatService.class)) {
            assertEquals("连接正常", service.simpleChat("用户正文\r\nFAKE_WARN"));

            assertEquals(
                    "快速对话开始 provider=ollama model=mymodel-base",
                    logs.eventStartingWith("快速对话开始").getFormattedMessage());
            assertLogsExclude(logs, "用户正文", "FAKE_WARN");
        }
    }

    @Test
    void chatLogsOnlyTrustedRagDiagnostics() {
        RagRetrievalService retrievalService = mock(RagRetrievalService.class);
        QueryRewriteResult rewrite = new QueryRewriteResult(
                "原始问题\r\nFAKE_ORIGINAL",
                "规范问题\r\nFAKE_NORMALIZED",
                "检索问题\r\nFAKE_RETRIEVAL",
                QueryIntent.SKIP_RAG,
                List.of("秘密文件\r\nFAKE_HINT"),
                true,
                QueryRewriteStrategy.RULE_NORMALIZED);
        when(retrievalService.retrieve(anyString(), anyList()))
                .thenReturn(new RagRetrievalService.RetrievalResult(
                        List.of(),
                        List.of(),
                        rewrite,
                        List.of(),
                        RagRetrievalService.RetrievalDiagnostics.skipped()));
        ChatModel model = prompt -> new ChatResponse(
                List.of(new Generation(new AssistantMessage("模型回答\r\nFAKE_REPLY"))));
        ChatService service = createService(model, retrievalService);

        try (LogCapture logs = LogCapture.forClass(ChatService.class)) {
            service.chat(
                    "用户问题\r\nFAKE_MESSAGE",
                    "系统提示\r\nFAKE_SYSTEM",
                    true,
                    List.of(),
                    "POST");

            assertEquals(
                    "RAG 跳过 useRag=true intent=SKIP_RAG",
                    logs.eventStartingWith("RAG 跳过").getFormattedMessage());
            assertEquals(
                    "RAG 问题改写 intent=SKIP_RAG strategy=RULE_NORMALIZED",
                    logs.eventStartingWith("RAG 问题改写").getFormattedMessage());
            assertEquals(
                    "调用本地模型开始 provider=ollama model=mymodel-base useRag=true",
                    logs.eventStartingWith("调用本地模型开始").getFormattedMessage());
            assertFalse(logs.eventStartingWith("调用本地模型完成")
                    .getFormattedMessage()
                    .contains("reply="));
            assertLogsExclude(
                    logs,
                    "原始问题",
                    "规范问题",
                    "检索问题",
                    "秘密文件",
                    "用户问题",
                    "系统提示",
                    "模型回答",
                    "FAKE_");
        }
    }

    @Test
    void chatRetrievalFailureLogOmitsUserContentAndThrowable() {
        RagRetrievalService retrievalService = mock(RagRetrievalService.class);
        when(retrievalService.retrieve(anyString(), anyList()))
                .thenThrow(new IllegalStateException("检索失败\r\nFAKE_ERROR"));
        ChatModel model = prompt -> new ChatResponse(
                List.of(new Generation(new AssistantMessage("回退回答\r\nFAKE_REPLY"))));
        ChatService service = createService(model, retrievalService);

        try (LogCapture logs = LogCapture.forClass(ChatService.class)) {
            service.chat(
                    "失败场景用户问题\r\nFAKE_MESSAGE",
                    "失败场景系统提示\r\nFAKE_SYSTEM",
                    true,
                    List.of(),
                    "POST");

            var failure = logs.eventStartingWith("RAG 检索失败");
            assertEquals(
                    "RAG 检索失败，回退为普通对话 errorType=IllegalStateException",
                    failure.getFormattedMessage());
            assertNull(failure.getThrowableProxy());
            assertLogsExclude(
                    logs,
                    "失败场景用户问题",
                    "失败场景系统提示",
                    "回退回答",
                    "FAKE_");
        }
    }
}
