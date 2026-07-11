package com.example.myllm.harness.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** 将工具输入转换为不包含实际参数值的结构化审计摘要。 */
@Component
public class ToolArgumentAuditSummarizer {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_FIELDS = 32;
    private static final int MAX_ELEMENT_TYPES = 8;
    private static final int MAX_JSON_BYTES = 4096;
    private static final String TYPE_STRING = "string";
    private static final String SAFE_FALLBACK_JSON =
            "{\"schemaVersion\":1,\"summary\":{\"type\":\"unavailable\"}}";

    private final ObjectMapper objectMapper;

    public ToolArgumentAuditSummarizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 根据工具声明类型生成结构摘要；无输入工具返回 {@code null}。
     *
     * @param declaredInputType 工具声明的输入类型
     * @param input 原始工具输入
     * @return 结构摘要 JSON，或无输入时返回 {@code null}
     */
    public String summarize(Class<?> declaredInputType, Object input) {
        if (input == null || Void.class.equals(declaredInputType) || void.class.equals(declaredInputType)) {
            return null;
        }
        try {
            byte[] json = buildSummary(declaredInputType, input);
            return json.length <= MAX_JSON_BYTES
                    ? new String(json, StandardCharsets.UTF_8)
                    : SAFE_FALLBACK_JSON;
        } catch (Exception exception) {
            // Jackson 异常可能包含原始值，不记录异常内容，只返回固定安全摘要。
            return SAFE_FALLBACK_JSON;
        }
    }

    private byte[] buildSummary(Class<?> declaredInputType, Object input) throws JsonProcessingException {
        Object normalized = normalizeInput(declaredInputType, input);
        JsonNode valueTree = objectMapper.valueToTree(normalized);
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("inputType", declaredInputType.getSimpleName());
        envelope.set("summary", summarizeNode(objectMapper.constructType(declaredInputType), valueTree, 0));
        return objectMapper.writeValueAsBytes(envelope);
    }

    private Object normalizeInput(Class<?> declaredInputType, Object input) {
        if (declaredInputType.isInstance(input)) {
            return input;
        }
        if (input instanceof Map<?, ?>) {
            return objectMapper.convertValue(input, declaredInputType);
        }
        throw new IllegalArgumentException("工具输入与声明类型不匹配");
    }

    private ObjectNode summarizeNode(JavaType declaredType, JsonNode value, int depth) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return typeNode("null");
        }
        if (value.isTextual()) {
            ObjectNode summary = typeNode(TYPE_STRING);
            summary.put("length", value.textValue().length());
            return summary;
        }
        if (value.isNumber()) {
            return typeNode("number");
        }
        if (value.isBoolean()) {
            return typeNode("boolean");
        }
        if (value.isArray()) {
            return summarizeArray(value);
        }
        if (value.isObject()) {
            return summarizeObject(declaredType, value, depth);
        }
        ObjectNode summary = typeNode(TYPE_STRING);
        summary.put("length", value.asText().length());
        return summary;
    }

    private ObjectNode summarizeObject(JavaType declaredType, JsonNode value, int depth) {
        ObjectNode summary = typeNode("object");
        if (isDynamicObjectType(declaredType)) {
            summary.put("fieldCount", value.size());
            return summary;
        }
        if (depth >= MAX_DEPTH) {
            summary.put("truncated", true);
            return summary;
        }

        ObjectNode fields = summary.putObject("fields");
        BeanDescription description = objectMapper.getSerializationConfig().introspect(declaredType);
        List<BeanPropertyDefinition> properties = description.findProperties().stream()
                .filter(BeanPropertyDefinition::couldSerialize)
                .sorted(Comparator.comparing(BeanPropertyDefinition::getName))
                .toList();
        if (properties.size() > MAX_FIELDS) {
            summary.put("truncated", true);
        }
        properties.stream()
                .limit(MAX_FIELDS)
                .forEach(property -> {
                    JsonNode propertyValue = value.get(property.getName());
                    fields.set(
                            property.getName(),
                            summarizeNode(
                                    property.getPrimaryType(),
                                    propertyValue == null ? NullNode.getInstance() : propertyValue,
                                    depth + 1));
                });
        return summary;
    }

    private static ObjectNode summarizeArray(JsonNode value) {
        ObjectNode summary = typeNode("array");
        summary.put("count", value.size());
        TreeSet<String> elementTypes = new TreeSet<>();
        value.forEach(element -> elementTypes.add(nodeType(element)));
        ArrayNode summarizedTypes = summary.putArray("elementTypes");
        elementTypes.stream()
                .limit(MAX_ELEMENT_TYPES)
                .forEach(summarizedTypes::add);
        return summary;
    }

    private static String nodeType(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "null";
        }
        if (value.isTextual()) {
            return TYPE_STRING;
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isObject()) {
            return "object";
        }
        return TYPE_STRING;
    }

    private static boolean isDynamicObjectType(JavaType declaredType) {
        Class<?> rawType = declaredType.getRawClass();
        return Object.class.equals(rawType) || Map.class.isAssignableFrom(rawType);
    }

    private static ObjectNode typeNode(String type) {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("type", type);
    }
}
