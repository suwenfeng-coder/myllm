package com.example.myllm.controller;

import com.example.myllm.dto.ChatRequest;
import com.example.myllm.dto.ChatResponse;
import com.example.myllm.service.ChatService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final String provider;

    public ChatController(ChatService chatService, @Value("${llm.provider:ollama}") String provider) {
        this.chatService = chatService;
        this.provider = provider;
    }

    @GetMapping("/health")
    public String health() {
        String modelInfo = chatService.getModelInfo();
        if (log.isDebugEnabled()) {
            log.debug("健康检查 provider={} model={}", provider, modelInfo);
        }
        return "ok - provider: " + provider + ", model: " + modelInfo;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        boolean useRag = request.useRagEnabled();
        if (log.isInfoEnabled()) {
            log.info("收到对话请求 POST /api/chat messageLen={} hasSystemPrompt={} chatMode={} useRag={}",
                    request.message().length(),
                    request.systemPrompt() != null && !request.systemPrompt().isBlank(),
                    request.resolvedChatMode(),
                    useRag);
        }
        return chatService.chat(
                request.message(),
                request.systemPrompt(),
                useRag,
                request.selectedFileIds(),
                useRag ? "POST" : "POST_DIRECT");
    }

    @GetMapping
    public ChatResponse quickChat(@RequestParam String message) {
        log.info("收到快速对话请求 GET /api/chat messageLen={}", message.length());
        return new ChatResponse(chatService.simpleChat(message), provider);
    }
}
