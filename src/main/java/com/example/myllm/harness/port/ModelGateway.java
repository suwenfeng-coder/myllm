package com.example.myllm.harness.port;

import com.example.myllm.harness.domain.HarnessAction;
import java.util.List;

/** 应用控制的模型调用端口（不启用 Spring AI 内部 Tool 循环）。 */
public interface ModelGateway {

    ModelResponse complete(ModelRequest request);

    record ModelRequest(
            String systemPrompt,
            String userPrompt,
            List<ToolDescriptor> availableTools,
            boolean repairFormat) {
    }

    record ModelResponse(
            String rawText,
            HarnessAction action,
            int inputTokens,
            int outputTokens,
            boolean success,
            String errorMessage) {

        public static ModelResponse ok(String rawText, HarnessAction action, int inputTokens, int outputTokens) {
            return new ModelResponse(rawText, action, inputTokens, outputTokens, true, null);
        }

        public static ModelResponse failed(String errorMessage) {
            return new ModelResponse(null, null, 0, 0, false, errorMessage);
        }
    }
}
