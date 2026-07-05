package com.example.myllm.harness.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 将模型 JSON 输出解析为 {@link HarnessAction}。 */
@Component
public class HarnessActionParser {

    private final ObjectMapper objectMapper;

    public HarnessActionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParseResult.failure("模型输出为空");
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(raw.trim()));
            String action = textOrNull(root, "action");
            if (action == null) {
                return ParseResult.failure("缺少 action 字段");
            }
            return switch (action) {
                case "CALL_TOOL" -> parseCallTool(root);
                case "FINAL" -> parseFinal(root);
                default -> ParseResult.failure("未知 action: " + action);
            };
        } catch (JsonProcessingException e) {
            return ParseResult.failure("JSON 解析失败: " + e.getOriginalMessage());
        }
    }

    private ParseResult parseCallTool(JsonNode root) {
        String tool = textOrNull(root, "tool");
        if (tool == null || tool.isBlank()) {
            return ParseResult.failure("CALL_TOOL 缺少 tool");
        }
        Map<String, Object> arguments = readArguments(root.get("arguments"));
        String summary = textOrNull(root, "summary");
        return ParseResult.success(new HarnessAction.CallTool(tool.trim(), arguments, summary));
    }

    private ParseResult parseFinal(JsonNode root) {
        String answer = textOrNull(root, "answer");
        if (answer == null || answer.isBlank()) {
            return ParseResult.failure("FINAL 缺少 answer");
        }
        List<HarnessAction.Citation> citations = new ArrayList<>();
        JsonNode citationsNode = root.get("citations");
        if (citationsNode != null && citationsNode.isArray()) {
            for (JsonNode item : citationsNode) {
                String sourceId = textOrNull(item, "sourceId");
                if (sourceId == null) {
                    sourceId = textOrNull(item, "source_id");
                }
                String excerpt = textOrNull(item, "excerpt");
                if (sourceId != null && !sourceId.isBlank()) {
                    citations.add(new HarnessAction.Citation(sourceId.trim(), excerpt == null ? "" : excerpt));
                }
            }
        }
        String summary = textOrNull(root, "summary");
        return ParseResult.success(new HarnessAction.Final(answer, citations, summary));
    }

    private static Map<String, Object> readArguments(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> map.put(entry.getKey(), jsonValue(entry.getValue())));
        return map;
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    static String extractJsonObject(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    public record ParseResult(HarnessAction action, String errorMessage) {

        public static ParseResult success(HarnessAction action) {
            return new ParseResult(action, null);
        }

        public static ParseResult failure(String message) {
            return new ParseResult(null, message);
        }

        public boolean success() {
            return action != null;
        }
    }
}
