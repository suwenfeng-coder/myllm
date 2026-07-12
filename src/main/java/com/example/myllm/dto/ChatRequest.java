package com.example.myllm.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequest(
        @NotBlank(message = "message 不能为空")
        String message,
        String systemPrompt,
        Boolean useRag,
        List<String> fileIds,
        String chatMode
) {
    private static final String DIRECT = "DIRECT";

    public boolean useRagEnabled() {
        if (chatMode != null && !chatMode.isBlank()) {
            return !DIRECT.equalsIgnoreCase(chatMode.trim());
        }
        return useRag == null || useRag;
    }

    public String resolvedChatMode() {
        if (chatMode != null && !chatMode.isBlank()) {
            return DIRECT.equalsIgnoreCase(chatMode.trim()) ? DIRECT : "RAG";
        }
        return useRagEnabled() ? "RAG" : DIRECT;
    }

    public List<String> selectedFileIds() {
        if (fileIds == null) {
            return List.of();
        }
        return fileIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .toList();
    }
}
