package com.example.myllm.harness.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
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
        } catch (IllegalArgumentException e) {
            return ParseResult.failure("工具参数结构解析失败");
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
        String summary = textOrNull(root, "summary");
        return ParseResult.success(new HarnessAction.Final(answer, parseCitations(root.get("citations")), summary));
    }

    private static List<HarnessAction.Citation> parseCitations(JsonNode citationsNode) {
        if (citationsNode == null || !citationsNode.isArray()) {
            return List.of();
        }
        List<HarnessAction.Citation> citations = new ArrayList<>();
        for (JsonNode item : citationsNode) {
            citationFrom(item).ifPresent(citations::add);
        }
        return List.copyOf(citations);
    }

    private static java.util.Optional<HarnessAction.Citation> citationFrom(JsonNode item) {
        String sourceId = firstText(item, "sourceId", "source_id");
        if (sourceId == null || sourceId.isBlank()) {
            return java.util.Optional.empty();
        }
        String excerpt = textOrNull(item, "excerpt");
        return java.util.Optional.of(new HarnessAction.Citation(sourceId.trim(), excerpt == null ? "" : excerpt));
    }

    private static String firstText(JsonNode node, String firstField, String secondField) {
        String first = textOrNull(node, firstField);
        return first == null ? textOrNull(node, secondField) : first;
    }

    private static Map<String, Object> readArguments(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return jsonObject(node);
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isArray()) {
            return jsonArray(node);
        }
        if (node.isObject()) {
            return jsonObject(node);
        }
        throw new IllegalArgumentException("不支持的工具参数节点类型");
    }

    private static Map<String, Object> jsonObject(JsonNode node) {
        Map<String, Object> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), jsonValue(entry.getValue())));
        return Collections.unmodifiableMap(values);
    }

    private static List<Object> jsonArray(JsonNode node) {
        List<Object> values = new ArrayList<>();
        node.forEach(item -> values.add(jsonValue(item)));
        return Collections.unmodifiableList(values);
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
