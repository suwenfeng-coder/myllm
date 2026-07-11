package com.example.myllm.harness.application;

import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** 为 Harness 工具原始参数生成稳定、无歧义的 v1 规范化哈希。 */
@Component
public class ToolArgumentHasher {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 10_000;
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final byte[] DOMAIN =
            "harness-tool-arguments-v1".getBytes(StandardCharsets.UTF_8);
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
            .build();

    public String hash(Object input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            digest.update((byte) 0);
            NodeBudget budget = new NodeBudget();
            IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
            try (JsonGenerator generator = JSON_FACTORY.createGenerator(
                    new BoundedDigestOutputStream(digest), JsonEncoding.UTF8)) {
                generator.disable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
                writeValue(generator, input, 0, budget, path);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ignored) {
            throw new HarnessDomainException(
                    HarnessErrorCode.VALIDATION_FAILED,
                    "工具参数无法安全规范化");
        }
    }

    private static void writeValue(
            JsonGenerator generator,
            Object value,
            int depth,
            NodeBudget budget,
            IdentityHashMap<Object, Boolean> path)
            throws ReflectiveOperationException, IOException {
        budget.enter(depth);
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof JsonNode node) {
            writeJsonNode(generator, node, depth, budget, path);
        } else if (value instanceof String text) {
            generator.writeString(text);
        } else if (value instanceof Character character) {
            generator.writeString(character.toString());
        } else if (value instanceof Enum<?> enumValue) {
            generator.writeString(enumValue.name());
        } else if (value instanceof Boolean booleanValue) {
            generator.writeBoolean(booleanValue);
        } else if (value instanceof Number number) {
            writeNumber(generator, number);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(generator, map, depth, budget, path);
        } else if (value instanceof List<?> list) {
            writeList(generator, list, depth, budget, path);
        } else if (value.getClass().isArray()) {
            writeArray(generator, value, depth, budget, path);
        } else if (value.getClass().isRecord()) {
            writeRecord(generator, value, depth, budget, path);
        } else {
            throw new HashingFailure();
        }
    }

    private static void writeMap(
            JsonGenerator generator,
            Map<?, ?> map,
            int depth,
            NodeBudget budget,
            IdentityHashMap<Object, Boolean> path) throws IOException, ReflectiveOperationException {
        budget.ensureChildren(map.size());
        enterPath(map, path);
        try {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new HashingFailure();
                }
                sorted.put(key, entry.getValue());
            }
            generator.writeStartObject();
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                generator.writeFieldName(entry.getKey());
                writeValue(generator, entry.getValue(), depth + 1, budget, path);
            }
            generator.writeEndObject();
        } finally {
            path.remove(map);
        }
    }

    private static void writeList(
            JsonGenerator generator,
            List<?> list,
            int depth,
            NodeBudget budget,
            IdentityHashMap<Object, Boolean> path) throws IOException, ReflectiveOperationException {
        budget.ensureChildren(list.size());
        enterPath(list, path);
        try {
            generator.writeStartArray();
            for (Object item : list) {
                writeValue(generator, item, depth + 1, budget, path);
            }
            generator.writeEndArray();
        } finally {
            path.remove(list);
        }
    }

    private static void writeArray(
            JsonGenerator generator,
            Object array,
            int depth,
            NodeBudget budget,
            IdentityHashMap<Object, Boolean> path) throws IOException, ReflectiveOperationException {
        int length = Array.getLength(array);
        budget.ensureChildren(length);
        enterPath(array, path);
        try {
            generator.writeStartArray();
            for (int index = 0; index < length; index++) {
                writeValue(generator, Array.get(array, index), depth + 1, budget, path);
            }
            generator.writeEndArray();
        } finally {
            path.remove(array);
        }
    }

    private static void writeRecord(
            JsonGenerator generator,
            Object record,
            int depth,
            NodeBudget budget,
            IdentityHashMap<Object, Boolean> path) throws ReflectiveOperationException, IOException {
        RecordComponent[] components = record.getClass().getRecordComponents();
        budget.ensureChildren(components.length);
        Arrays.sort(components, Comparator.comparing(RecordComponent::getName));
        enterPath(record, path);
        try {
            generator.writeStartObject();
            for (RecordComponent component : components) {
                Method accessor = component.getAccessor();
                if (!accessor.canAccess(record) && !accessor.trySetAccessible()) {
                    throw new HashingFailure();
                }
                generator.writeFieldName(component.getName());
                writeValue(generator, accessor.invoke(record), depth + 1, budget, path);
            }
            generator.writeEndObject();
        } finally {
            path.remove(record);
        }
    }

    private static void writeJsonNode(
            JsonGenerator generator,
            JsonNode node,
            int depth,
            NodeBudget budget,
            IdentityHashMap<Object, Boolean> path) throws IOException, ReflectiveOperationException {
        if (node.isNull()) {
            generator.writeNull();
        } else if (node.isTextual()) {
            generator.writeString(node.textValue());
        } else if (node.isBoolean()) {
            generator.writeBoolean(node.booleanValue());
        } else if (node.isNumber()) {
            writeNumber(generator, node.numberValue());
        } else if (node instanceof ObjectNode objectNode) {
            budget.ensureChildren(objectNode.size());
            enterPath(objectNode, path);
            try {
                TreeMap<String, JsonNode> sorted = new TreeMap<>();
                objectNode.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
                generator.writeStartObject();
                for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                    generator.writeFieldName(entry.getKey());
                    writeValue(generator, entry.getValue(), depth + 1, budget, path);
                }
                generator.writeEndObject();
            } finally {
                path.remove(objectNode);
            }
        } else if (node instanceof ArrayNode arrayNode) {
            budget.ensureChildren(arrayNode.size());
            enterPath(arrayNode, path);
            try {
                generator.writeStartArray();
                for (JsonNode item : arrayNode) {
                    writeValue(generator, item, depth + 1, budget, path);
                }
                generator.writeEndArray();
            } finally {
                path.remove(arrayNode);
            }
        } else {
            throw new HashingFailure();
        }
    }

    private static void writeNumber(JsonGenerator generator, Number number) throws IOException {
        if (number instanceof Byte || number instanceof Short || number instanceof Integer) {
            generator.writeNumber(number.intValue());
        } else if (number instanceof Long) {
            generator.writeNumber(number.longValue());
        } else if (number instanceof BigInteger bigInteger) {
            generator.writeNumber(bigInteger);
        } else if (number instanceof Float floatValue && Float.isFinite(floatValue)) {
            generator.writeNumber(floatValue);
        } else if (number instanceof Double doubleValue && Double.isFinite(doubleValue)) {
            generator.writeNumber(doubleValue);
        } else if (number instanceof BigDecimal bigDecimal) {
            generator.writeNumber(bigDecimal);
        } else {
            throw new HashingFailure();
        }
    }

    private static void enterPath(Object value, IdentityHashMap<Object, Boolean> path) {
        if (path.put(value, Boolean.TRUE) != null) {
            throw new HashingFailure();
        }
    }

    private static final class NodeBudget {

        private int count;

        private void enter(int depth) {
            if (depth > MAX_DEPTH || count >= MAX_NODES) {
                throw new HashingFailure();
            }
            count++;
        }

        private void ensureChildren(int children) {
            if (children < 0 || children > MAX_NODES - count) {
                throw new HashingFailure();
            }
        }
    }

    private static final class BoundedDigestOutputStream extends OutputStream {

        private final MessageDigest digest;
        private int count;

        private BoundedDigestOutputStream(MessageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void write(int value) {
            ensureCapacity(1);
            digest.update((byte) value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            ensureCapacity(length);
            digest.update(bytes, offset, length);
            count += length;
        }

        private void ensureCapacity(int length) {
            if (length < 0 || length > MAX_JSON_BYTES - count) {
                throw new HashingFailure();
            }
        }
    }

    private static final class HashingFailure extends RuntimeException {

        private HashingFailure() {
            super(null, null, false, false);
        }
    }
}
