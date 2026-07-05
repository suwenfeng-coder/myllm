package com.example.myllm.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRequestTests {

    @Test
    void defaultsToRagWhenUseRagIsNull() {
        ChatRequest request = new ChatRequest("hello", null, null, null, null);

        assertTrue(request.useRagEnabled());
        assertEquals("RAG", request.resolvedChatMode());
    }

    @Test
    void directModeOverridesUseRagTrue() {
        ChatRequest request = new ChatRequest("hello", null, true, List.of("file-1"), "direct");

        assertFalse(request.useRagEnabled());
        assertEquals("DIRECT", request.resolvedChatMode());
    }

    @Test
    void ragModeOverridesUseRagFalse() {
        ChatRequest request = new ChatRequest("hello", null, false, null, "RAG");

        assertTrue(request.useRagEnabled());
        assertEquals("RAG", request.resolvedChatMode());
    }

    @Test
    void useRagFalseResolvesDirectMode() {
        ChatRequest request = new ChatRequest("hello", null, false, null, null);

        assertFalse(request.useRagEnabled());
        assertEquals("DIRECT", request.resolvedChatMode());
    }
}
