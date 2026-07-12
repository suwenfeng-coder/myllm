package com.example.myllm.harness.adapter.model;

import com.example.myllm.harness.domain.HarnessActionParser;
import com.example.myllm.harness.port.ModelGateway;
import com.example.myllm.harness.port.ToolDescriptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Spring AI ChatClient 的模型网关。
 *
 * <p>不在 ChatClient 上注册 ToolCallback，由应用 {@code HarnessOrchestrator} 控制执行循环。</p>
 */
@Component
@ConditionalOnExpression("'${harness.enabled:false}' == 'true' and '${harness.model.gateway:spring-ai}' == 'spring-ai'")
public class SpringAiModelGateway implements ModelGateway {

    private final ChatClient chatClient;
    private final HarnessActionParser actionParser;

    public SpringAiModelGateway(ChatClient chatClient, HarnessActionParser actionParser) {
        this.chatClient = chatClient;
        this.actionParser = actionParser;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        try {
            String raw = chatClient.prompt()
                    .system(request.systemPrompt())
                    .user(request.userPrompt())
                    .call()
                    .content();
            HarnessActionParser.ParseResult parsed = actionParser.parse(raw);
            if (!parsed.success()) {
                return ModelResponse.failed(parsed.errorMessage());
            }
            return ModelResponse.ok(raw, parsed.action(), 0, 0);
        } catch (Exception e) {
            return ModelResponse.failed(e.getMessage() == null ? "模型调用失败" : e.getMessage());
        }
    }

    static String buildToolCatalog(List<ToolDescriptor> tools) {
        if (tools == null || tools.isEmpty()) {
            return "（无可用工具）";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolDescriptor tool : tools) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
        }
        return sb.toString();
    }
}
