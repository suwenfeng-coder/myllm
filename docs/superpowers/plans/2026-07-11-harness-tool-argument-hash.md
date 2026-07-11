# Harness 工具参数规范化哈希实施计划

> **供 Agent 执行：** 必须使用 `subagent-driven-development`（推荐）或 `executing-plans` 按任务实施；每个生产代码变更都要先看到对应测试按预期失败。所有步骤使用复选框跟踪。

**目标：** 用稳定的 v1 规范 JSON SHA-256 替换 `String.valueOf(input)` 参数哈希，使对象字段顺序不影响审计哈希和自动幂等键，同时保持数组、空值和标量差异。

**架构：** 新增单职责 `ToolArgumentHasher`，使用专用 Jackson `JsonGenerator` 把固定 Java 输入域直接写入有界摘要输出流，不构建完整规范 JSON 中间副本。`ToolExecutor` 仅在有 `runId` 的持久化路径计算一次哈希，并把同一结果交给自动幂等键和审计记录。

**技术栈：** Java 17、Spring Boot 3.4.1、Jackson Core 2.18.2、JUnit 5、Mockito、Spring Data JPA、Maven。

## 全局约束

- 哈希对象是进入 `ToolExecutor` 的原始参数，不提前转换为工具声明 Record。
- 对象字段在每一层按 Java `String` 自然顺序排列；List、Java 数组和 `ArrayNode` 保持原顺序。
- 字段缺失与显式 `null` 不合并；`null`、空字符串和空对象不同；数字 `1` 与 `1.0` 不同。
- v1 域固定为 UTF-8 `harness-tool-arguments-v1`，域后写一个零字节，再写规范 JSON UTF-8。
- 固定向量必须得到 `ddc0ee6120d602754b8251a3528ac27dae57580b079ae59dfb169623d14f1980`。
- 最大深度固定为 32，根节点深度为 0；最大节点数固定为 10,000；规范 JSON 最大 1,048,576 字节。
- 规范 JSON 必须直接写入有界摘要输出流，禁止完整写入 `byte[]`、String、日志、数据库或临时文件。
- 允许输入仅限：`null`、String、Character、Enum、Boolean、规定的 Number、`Map<String, ?>`、List、Java 数组、Record 和标准 `JsonNode`。
- 普通 POJO、Set、其他 Iterable、非字符串 Map 键、未知 Number、非有限数字和非标准 JsonNode 必须固定失败。
- 失败统一抛出 `HarnessDomainException(VALIDATION_FAILED, "工具参数无法安全规范化")`，不设置 cause，不包含原始值。
- 哈希失败发生在幂等查询、审计写入和工具执行前；禁止 fallback 哈希。
- 无 `runId` 的临时调用不计算哈希；显式幂等键继续只做 `trim()`。
- 审计摘要继续 fail-soft；参数哈希保持 fail-closed。
- 不修改 Parser、工具输入 Record、实体、DDL、迁移脚本、显式幂等命中条件或 H6 审批功能。
- 不删除任何文件；新增文档、代码注释和提交说明全部使用中文。

---

### 任务 1：实现独立 ToolArgumentHasher

**文件：**

- 新增：`src/main/java/com/example/myllm/harness/application/ToolArgumentHasher.java`
- 新增：`src/test/java/com/example/myllm/harness/application/ToolArgumentHasherTests.java`

**接口：**

- 产出：Spring 组件 `ToolArgumentHasher`。
- 产出：`public String hash(Object input)`。
- 错误：只抛出固定 `HarnessDomainException`，错误码为 `VALIDATION_FAILED`。
- 不依赖：应用共享 `ObjectMapper`、工具声明类型、Repository 或配置文件。

- [x] **步骤 1：先写完整的规范化哈希失败测试**

创建 `ToolArgumentHasherTests.java`，写入以下测试类：

```java
package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ShortNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolArgumentHasherTests {

    private static final String FIXED_HASH =
            "ddc0ee6120d602754b8251a3528ac27dae57580b079ae59dfb169623d14f1980";

    private final ToolArgumentHasher hasher = new ToolArgumentHasher();

    @Test
    void ignoresObjectOrderAtEveryDepthAndMatchesFixedVector() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("query", "制度依据");
        first.put("fileIds", List.of("file-1", "file-2"));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("fileIds", List.of("file-1", "file-2"));
        second.put("query", "制度依据");

        assertEquals(FIXED_HASH, hasher.hash(first));
        assertEquals(hasher.hash(first), hasher.hash(second));

        Map<String, Object> nestedFirst = new LinkedHashMap<>();
        nestedFirst.put("enabled", true);
        nestedFirst.put("threshold", 1.0);
        Map<String, Object> nestedSecond = new LinkedHashMap<>();
        nestedSecond.put("threshold", 1.0);
        nestedSecond.put("enabled", true);
        assertEquals(
                hasher.hash(Map.of("filters", nestedFirst)),
                hasher.hash(Map.of("filters", nestedSecond)));
    }

    @Test
    void preservesArrayNullAndScalarDifferences() {
        assertNotEquals(hasher.hash(List.of("a", "b")), hasher.hash(List.of("b", "a")));
        assertNotEquals(hasher.hash(null), hasher.hash(""));
        assertNotEquals(hasher.hash(""), hasher.hash(Map.of()));
        assertNotEquals(
                hasher.hash(Map.of()),
                hasher.hash(Collections.singletonMap("value", null)));
        assertNotEquals(hasher.hash(1), hasher.hash(1.0));
        assertNotEquals(hasher.hash(1), hasher.hash("1"));
    }

    @Test
    void supportsTheFixedScalarDomain() {
        assertEquals(hasher.hash(1), hasher.hash((byte) 1));
        assertEquals(hasher.hash(1), hasher.hash((short) 1));
        assertEquals(hasher.hash(1), hasher.hash(1L));
        assertEquals(hasher.hash(1), hasher.hash(BigInteger.ONE));
        assertEquals(hasher.hash(1.0), hasher.hash(1.0F));
        assertEquals(hasher.hash(1.0), hasher.hash(new BigDecimal("1.0")));
        assertEquals(hasher.hash("A"), hasher.hash('A'));
        assertEquals(hasher.hash("READY"), hasher.hash(Status.READY));
    }

    @Test
    void separatesStructuresThatHadTheSameMapText() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("query", "x, fileIds=[y]");
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("query", "x");
        structured.put("fileIds", List.of("y"));

        assertEquals(flat.toString(), structured.toString());
        assertNotEquals(hasher.hash(flat), hasher.hash(structured));
    }

    @Test
    void hashesEquivalentMapAndRecordTheSame() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fileIds", List.of("f1", "f2"));
        map.put("query", "制度依据");

        assertEquals(
                hasher.hash(map),
                hasher.hash(new SearchInput("制度依据", List.of("f1", "f2"))));
    }

    @Test
    @DisplayName("Jackson 标准数值节点具体类型与对应 Java 数值生成相同哈希")
    void hashesEveryAllowedStandardNumericNodeLikeItsJavaNumber() {
        assertEquals(hasher.hash((short) 7), hasher.hash(ShortNode.valueOf((short) 7)));
        assertEquals(hasher.hash(7), hasher.hash(IntNode.valueOf(7)));
        assertEquals(hasher.hash(7L), hasher.hash(LongNode.valueOf(7L)));
        assertEquals(
                hasher.hash(BigInteger.valueOf(7L)),
                hasher.hash(BigIntegerNode.valueOf(BigInteger.valueOf(7L))));
        assertEquals(hasher.hash(1.25F), hasher.hash(FloatNode.valueOf(1.25F)));
        assertEquals(hasher.hash(1.25D), hasher.hash(DoubleNode.valueOf(1.25D)));
        assertEquals(
                hasher.hash(new BigDecimal("1.25")),
                hasher.hash(DecimalNode.valueOf(new BigDecimal("1.25"))));
    }

    @Test
    @DisplayName("自定义 TextNode 在顶层和嵌套入口均固定失败")
    void rejectsCustomTextNodeAtTopLevelAndNestedEntrypoints() {
        assertFixedFailureAtTopLevelAndNested(new CustomTextNode("密钥-不得泄露"));
    }

    @Test
    @DisplayName("自定义数值节点在顶层和嵌套入口均固定失败")
    void rejectsCustomNumericNodeAtTopLevelAndNestedEntrypoints() {
        assertFixedFailureAtTopLevelAndNested(new CustomIntNode(7));
    }

    @Test
    @DisplayName("自定义 ObjectNode 在顶层和嵌套入口均固定失败")
    void rejectsCustomObjectNodeAtTopLevelAndNestedEntrypoints() {
        assertFixedFailureAtTopLevelAndNested(new MisreportingObjectNode());
    }

    @Test
    @DisplayName("自定义 ArrayNode 在顶层和嵌套入口均固定失败")
    void rejectsCustomArrayNodeAtTopLevelAndNestedEntrypoints() {
        assertFixedFailureAtTopLevelAndNested(new CustomArrayNode());
    }

    @Test
    @DisplayName("BigInteger 子类在顶层和 Map、Record 嵌套入口均固定失败")
    void rejectsBigIntegerSubclassAtTopLevelAndNestedEntrypoints() {
        CustomBigInteger input = new CustomBigInteger("7");

        assertFixedFailure(input);
        assertFixedFailure(Map.of("value", input));
        assertFixedFailure(new RecordWithValue(input));
    }

    @Test
    @DisplayName("BigDecimal 子类在顶层和 Map、Record 嵌套入口均固定失败")
    void rejectsBigDecimalSubclassAtTopLevelAndNestedEntrypoints() {
        CustomBigDecimal input = new CustomBigDecimal("1.25");

        assertFixedFailure(input);
        assertFixedFailure(Map.of("value", input));
        assertFixedFailure(new RecordWithValue(input));
    }

    @Test
    void rejectsUnsupportedValuesWithFixedFailure() {
        assertFixedFailure(Map.of(1, "value"));
        assertFixedFailure(Double.NaN);
        assertFixedFailure(Double.POSITIVE_INFINITY);
        assertFixedFailure(Float.NEGATIVE_INFINITY);
        assertFixedFailure(new Object());
        assertFixedFailure(Set.of("a", "b"));
        assertFixedFailure(new UnsupportedNumber());
        assertFixedFailure(JsonNodeFactory.instance.binaryNode(new byte[]{1}));
        assertFixedFailure(JsonNodeFactory.instance.pojoNode(new Object()));
        assertFixedFailure(JsonNodeFactory.instance.missingNode());
    }

    @Test
    void rejectsNestedNonStringMapKeysInsideRecord() {
        assertFixedFailure(new RecordWithMap(Map.of(1, "value")));
    }

    @Test
    void rejectsCircularDeepLargeAndOversizedValues() {
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertFixedFailure(cycle);

        Object deep = "leaf";
        for (int index = 0; index < 33; index++) {
            deep = List.of(deep);
        }
        assertFixedFailure(deep);
        assertFixedFailure(Collections.nCopies(10_000, "node"));
        assertFixedFailure("密".repeat(400_000));

        ObjectNode oversizedNode = JsonNodeFactory.instance.objectNode();
        for (int index = 0; index < 10_000; index++) {
            oversizedNode.put("f" + index, index);
        }
        assertFixedFailure(oversizedNode);
    }

    @Test
    void rejectsThrowingRecordWithoutLeakingSecret() {
        HarnessDomainException exception = assertThrows(
                HarnessDomainException.class,
                () -> hasher.hash(new ThrowingRecord("密钥-不得泄露")));

        assertEquals(HarnessErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertEquals("工具参数无法安全规范化", exception.getMessage());
        assertFalse(exception.getMessage().contains("密钥-不得泄露"));
        assertNull(exception.getCause());
    }

    private void assertFixedFailure(Object input) {
        HarnessDomainException exception = assertThrows(
                HarnessDomainException.class,
                () -> hasher.hash(input));
        assertEquals(HarnessErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertEquals("工具参数无法安全规范化", exception.getMessage());
        assertNull(exception.getCause());
    }

    private void assertFixedFailureAtTopLevelAndNested(Object input) {
        assertFixedFailure(input);
        assertFixedFailure(Map.of("value", input));
        assertFixedFailure(List.of(input));
        assertFixedFailure(new RecordWithValue(input));
    }

    private enum Status {
        READY
    }

    private record SearchInput(String query, List<String> fileIds) {
    }

    private record RecordWithMap(Map<?, ?> values) {
    }

    private record RecordWithValue(Object value) {
    }

    private record ThrowingRecord(String secret) {

        @Override
        public String secret() {
            throw new IllegalStateException("密钥-不得泄露");
        }
    }

    private static final class UnsupportedNumber extends Number {

        @Override
        public int intValue() {
            return 1;
        }

        @Override
        public long longValue() {
            return 1L;
        }

        @Override
        public float floatValue() {
            return 1.0F;
        }

        @Override
        public double doubleValue() {
            return 1.0;
        }
    }

    private static final class CustomTextNode extends TextNode {

        private CustomTextNode(String value) {
            super(value);
        }
    }

    private static final class CustomIntNode extends IntNode {

        private CustomIntNode(int value) {
            super(value);
        }
    }

    private static final class MisreportingObjectNode extends ObjectNode {

        private MisreportingObjectNode() {
            super(JsonNodeFactory.instance);
            put("value", 7);
        }

        /** 故意让已知大小与实际字段数不一致，验证子类不能绕过预分配保护。 */
        @Override
        public int size() {
            return 0;
        }
    }

    private static final class CustomArrayNode extends ArrayNode {

        private CustomArrayNode() {
            super(JsonNodeFactory.instance);
        }
    }

    private static final class CustomBigInteger extends BigInteger {

        private CustomBigInteger(String value) {
            super(value);
        }
    }

    private static final class CustomBigDecimal extends BigDecimal {

        private CustomBigDecimal(String value) {
            super(value);
        }
    }
}
```

- [x] **步骤 2：运行测试并确认按预期失败**

运行：

```bash
mvn -q -Dtest=ToolArgumentHasherTests test
```

预期：测试编译失败，明确提示 `ToolArgumentHasher` 不存在。不得先创建空壳类绕过红灯。

- [x] **步骤 3：实现固定输入域、流式规范 JSON 和 SHA-256**

创建 `ToolArgumentHasher.java`，写入：

```java
package com.example.myllm.harness.application;

import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ShortNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
        Class<?> nodeType = node.getClass();
        if (nodeType == NullNode.class) {
            generator.writeNull();
        } else if (nodeType == TextNode.class) {
            generator.writeString(node.textValue());
        } else if (nodeType == BooleanNode.class) {
            generator.writeBoolean(node.booleanValue());
        } else if (nodeType == ShortNode.class
                || nodeType == IntNode.class
                || nodeType == LongNode.class
                || nodeType == BigIntegerNode.class
                || nodeType == FloatNode.class
                || nodeType == DoubleNode.class
                || nodeType == DecimalNode.class) {
            writeNumber(generator, node.numberValue());
        } else if (nodeType == ObjectNode.class) {
            ObjectNode objectNode = (ObjectNode) node;
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
        } else if (nodeType == ArrayNode.class) {
            ArrayNode arrayNode = (ArrayNode) node;
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
        Class<?> numberType = number.getClass();
        if (numberType == Byte.class || numberType == Short.class || numberType == Integer.class) {
            generator.writeNumber(number.intValue());
        } else if (numberType == Long.class) {
            generator.writeNumber(number.longValue());
        } else if (numberType == BigInteger.class) {
            generator.writeNumber((BigInteger) number);
        } else if (numberType == Float.class && Float.isFinite((Float) number)) {
            Float floatValue = (Float) number;
            generator.writeNumber(floatValue);
        } else if (numberType == Double.class && Double.isFinite((Double) number)) {
            Double doubleValue = (Double) number;
            generator.writeNumber(doubleValue);
        } else if (numberType == BigDecimal.class) {
            generator.writeNumber((BigDecimal) number);
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
```

- [x] **步骤 4：运行单元测试并确认全部通过**

运行：

```bash
mvn -q -Dtest=ToolArgumentHasherTests test
```

预期：所有 `ToolArgumentHasherTests` 通过；固定向量精确匹配，失败信封不包含秘密值。

- [x] **步骤 5：提交任务 1**

```bash
git add src/main/java/com/example/myllm/harness/application/ToolArgumentHasher.java \
  src/test/java/com/example/myllm/harness/application/ToolArgumentHasherTests.java
git commit -m "安全：实现工具参数规范化哈希"
```

---

### 任务 2：在 ToolExecutor 中复用单次哈希

**文件：**

- 修改：`src/main/java/com/example/myllm/harness/application/ToolExecutor.java:16-29,44-65,75-110,237-273`
- 修改：`src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java`

**接口：**

- 消费：`ToolArgumentHasher.hash(Object)`。
- 保持：`ToolResult<Object> execute(ToolExecutionContext context, String toolName, Object input)`。
- 保持：显式幂等键只做 `trim()`；无 `runId` 不写审计。
- 产出：`createAuditRecord` 接收已计算的 `argumentsHash`，不再自行计算。

- [x] **步骤 1：增加计数 Hasher、Repository Spy 和测试工具**

修改 `ToolExecutorTests` 导入：

```java
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
```

从 `@Import` 中移除 `ToolArgumentAuditSummarizer.class`；它将在 `TestConfig` 中显式创建测试 Bean。

将 Repository 字段改为 Spy，并注入测试计数器：

```java
@MockitoSpyBean
private HarnessToolCallRepository toolCallRepository;

@Autowired
private CountingToolArgumentHasher argumentHasher;

@Autowired
private CountingCanonicalTool canonicalTool;

@Autowired
private HashFailureTestTool hashFailureTool;

@BeforeEach
void resetCounters() {
    argumentHasher.reset();
    canonicalTool.reset();
    hashFailureTool.reset();
    clearInvocations(toolCallRepository);
}
```

在 `TestConfig` 中增加常量、Bean 和 Registry 注册。`toolRegistry` 的完整签名与注册顺序改为：

```java
static final String CANONICAL_TEST = "canonical.test";
static final String HASH_FAILURE_TEST = "hash.failure.test";

@Bean
@Primary
ToolRegistry toolRegistry(
        EchoTestTool echoTestTool,
        NullResultTool nullResultTool,
        VoidTestTool voidTestTool,
        AuditFailureTestTool auditFailureTestTool,
        CountingCanonicalTool canonicalTool,
        HashFailureTestTool hashFailureTool) {
    DefaultToolRegistry registry = new DefaultToolRegistry(java.util.List.of());
    registry.register(echoTestTool);
    registry.register(nullResultTool);
    registry.register(voidTestTool);
    registry.register(auditFailureTestTool);
    registry.register(canonicalTool);
    registry.register(hashFailureTool);
    return registry;
}

@Bean
CountingToolArgumentHasher toolArgumentHasher() {
    return new CountingToolArgumentHasher();
}

@Bean
ToolArgumentAuditSummarizer toolArgumentAuditSummarizer(ObjectMapper objectMapper) {
    return new TestToolArgumentAuditSummarizer(objectMapper);
}

@Bean
CountingCanonicalTool canonicalTool() {
    return new CountingCanonicalTool();
}

@Bean
HashFailureTestTool hashFailureTool() {
    return new HashFailureTestTool();
}
```

把 `AuditFailureInput` 改为可安全哈希的 Record，并增加以下测试类：

```java
record AuditFailureInput(String marker) {
}

record CanonicalInput(String query, List<String> fileIds) {
}

static final class HashFailureInput {

    public String getSecret() {
        throw new IllegalStateException("密钥-不得泄露");
    }
}

static class CountingToolArgumentHasher extends ToolArgumentHasher {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile String lastHash;

    @Override
    public String hash(Object input) {
        calls.incrementAndGet();
        lastHash = super.hash(input);
        return lastHash;
    }

    int calls() {
        return calls.get();
    }

    String lastHash() {
        return lastHash;
    }

    void reset() {
        calls.set(0);
        lastHash = null;
    }
}

static class TestToolArgumentAuditSummarizer extends ToolArgumentAuditSummarizer {

    private static final String FALLBACK =
            "{\"schemaVersion\":1,\"summary\":{\"type\":\"unavailable\"}}";

    TestToolArgumentAuditSummarizer(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String summarize(Class<?> declaredInputType, Object input) {
        if (AuditFailureInput.class.equals(declaredInputType)) {
            return FALLBACK;
        }
        return super.summarize(declaredInputType, input);
    }
}

static class CountingCanonicalTool implements HarnessTool<CanonicalInput, String> {

    private final AtomicInteger executions = new AtomicInteger();

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                TestConfig.CANONICAL_TEST,
                "1",
                "规范化参数测试工具",
                ToolRisk.READ_ONLY,
                5000,
                true,
                false,
                4096);
    }

    @Override
    public Class<CanonicalInput> inputType() {
        return CanonicalInput.class;
    }

    @Override
    public ToolResult<String> execute(ToolExecutionContext context, CanonicalInput input) {
        executions.incrementAndGet();
        return ToolResult.ok("done", "canonical:" + input.query(), 1);
    }

    int executions() {
        return executions.get();
    }

    void reset() {
        executions.set(0);
    }
}

static class HashFailureTestTool implements HarnessTool<HashFailureInput, String> {

    private final AtomicInteger executions = new AtomicInteger();

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                TestConfig.HASH_FAILURE_TEST,
                "1",
                "参数哈希失败测试工具",
                ToolRisk.READ_ONLY,
                5000,
                true,
                false,
                4096);
    }

    @Override
    public Class<HashFailureInput> inputType() {
        return HashFailureInput.class;
    }

    @Override
    public ToolResult<String> execute(ToolExecutionContext context, HashFailureInput input) {
        executions.incrementAndGet();
        return ToolResult.ok("done", "hash-failure-input-executed", 1);
    }

    int executions() {
        return executions.get();
    }

    void reset() {
        executions.set(0);
    }
}
```

现有 `auditFallbackDoesNotBlockToolExecution` 中的输入改为：

```java
new AuditFailureInput("触发固定摘要")
```

- [x] **步骤 2：先写 ToolExecutor 集成失败测试**

在 `ToolExecutorTests` 增加以下测试，并在现有 `executesAllowedToolAndPersistsAudit` 末尾增加单次哈希断言：

```java
@Test
void canonicalAutomaticIdempotencyHashesOncePerCallAndExecutesOnce() {
    HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
            "knowledge-assistant", 1, "hash", RunType.AGENT_LOOP, "obj", "req-canonical-auto", null, null, 8));
    ToolExecutionContext context = new ToolExecutionContext(
            run.getRunId(), null, null, Set.of(TestConfig.CANONICAL_TEST));
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("query", "制度依据");
    first.put("fileIds", List.of("f1", "f2"));
    Map<String, Object> second = new LinkedHashMap<>();
    second.put("fileIds", List.of("f1", "f2"));
    second.put("query", "制度依据");

    ToolResult<?> firstResult = toolExecutor.execute(context, TestConfig.CANONICAL_TEST, first);
    assertTrue(firstResult.success());
    assertEquals(1, argumentHasher.calls());
    String firstHash = argumentHasher.lastHash();

    ToolResult<?> secondResult = toolExecutor.execute(context, TestConfig.CANONICAL_TEST, second);
    assertTrue(secondResult.success());
    assertEquals(2, argumentHasher.calls());
    assertEquals(firstHash, argumentHasher.lastHash());
    assertEquals(1, canonicalTool.executions());

    var calls = toolCallRepository.findAll();
    assertEquals(1, calls.size());
    assertEquals(firstHash, calls.get(0).getArgumentsHash());
    assertEquals(TestConfig.CANONICAL_TEST + ":" + firstHash, calls.get(0).getIdempotencyKey());
}

@Test
void explicitIdempotencyStillHashesExactlyOnceForAudit() {
    HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
            "knowledge-assistant", 1, "hash", RunType.AGENT_LOOP, "obj", "req-canonical-explicit", null, null, 8));
    ToolExecutionContext context = new ToolExecutionContext(
            run.getRunId(), null, "  explicit-key  ", Set.of(TestConfig.ECHO_TEST));

    ToolResult<?> result = toolExecutor.execute(context, TestConfig.ECHO_TEST, "hello");

    assertTrue(result.success());
    assertEquals(1, argumentHasher.calls());
    var call = toolCallRepository.findAll().get(0);
    assertEquals("explicit-key", call.getIdempotencyKey());
    assertEquals(argumentHasher.lastHash(), call.getArgumentsHash());
}

@Test
void hashFailureHappensBeforeRepositoryAndToolExecution() {
    HarnessRun run = harnessRunService.createRun(new HarnessRunService.CreateRunCommand(
            "knowledge-assistant", 1, "hash", RunType.AGENT_LOOP, "obj", "req-hash-failure", null, null, 8));
    ToolExecutionContext context = new ToolExecutionContext(
            run.getRunId(), null, "explicit-failure", Set.of(TestConfig.HASH_FAILURE_TEST));
    clearInvocations(toolCallRepository);

    HarnessDomainException exception = assertThrows(
            HarnessDomainException.class,
            () -> toolExecutor.execute(context, TestConfig.HASH_FAILURE_TEST, new HashFailureInput()));

    assertEquals(HarnessErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    assertEquals("工具参数无法安全规范化", exception.getMessage());
    assertNull(exception.getCause());
    assertEquals(1, argumentHasher.calls());
    assertEquals(0, hashFailureTool.executions());
    verifyNoInteractions(toolCallRepository);
}

@Test
void temporaryCallWithoutRunIdSkipsHashing() {
    ToolExecutionContext context = new ToolExecutionContext(
            null, null, null, Set.of(TestConfig.HASH_FAILURE_TEST));

    ToolResult<?> result = toolExecutor.execute(
            context, TestConfig.HASH_FAILURE_TEST, new HashFailureInput());

    assertTrue(result.success());
    assertEquals(0, argumentHasher.calls());
    assertEquals(1, hashFailureTool.executions());
}
```

在 `executesAllowedToolAndPersistsAudit` 末尾追加：

```java
assertEquals(1, argumentHasher.calls());
assertEquals(argumentHasher.lastHash(), toolCallRepository.findAll().get(0).getArgumentsHash());
```

- [x] **步骤 3：运行集成测试并确认按预期失败**

运行：

```bash
mvn -q -Dtest=ToolExecutorTests test
```

预期：至少出现以下失败：计数 Hasher 调用次数为 0；字段顺序不同的自动键产生两条调用；规范化失败输入仍进入 Repository 或工具执行。现有审计摘要、结果大小和显式重放测试继续编译。

- [x] **步骤 4：修改 ToolExecutor 只计算并复用一次哈希**

在字段与构造函数中加入依赖：

```java
private final ToolArgumentHasher argumentHasher;

public ToolExecutor(
        ToolRegistry toolRegistry,
        ToolPolicyEngine policyEngine,
        HarnessToolCallRepository toolCallRepository,
        HarnessProperties properties,
        ObjectMapper objectMapper,
        ToolArgumentAuditSummarizer argumentAuditSummarizer,
        ToolArgumentHasher argumentHasher) {
    this.toolRegistry = toolRegistry;
    this.policyEngine = policyEngine;
    this.toolCallRepository = toolCallRepository;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.argumentAuditSummarizer = argumentAuditSummarizer;
    this.argumentHasher = argumentHasher;
}
```

把 `execute` 中策略校验后的哈希与幂等准备改为：

```java
boolean persistAudit = hasRunId(safeContext);
String argumentsHash = persistAudit ? argumentHasher.hash(input) : null;
String idempotencyKey = persistAudit
        ? resolveIdempotencyKey(safeContext, toolName, argumentsHash)
        : null;
```

创建审计记录时传入同一个哈希：

```java
audit = createAuditRecord(
        safeContext,
        descriptor,
        tool.inputType(),
        idempotencyKey,
        argumentsHash,
        input);
```

修改审计方法签名和赋值：

```java
private HarnessToolCall createAuditRecord(
        ToolExecutionContext context,
        ToolDescriptor descriptor,
        Class<?> declaredInputType,
        String idempotencyKey,
        String argumentsHash,
        Object input) {
    HarnessToolCall auditRecord = new HarnessToolCall();
    auditRecord.setToolCallId(UUID.randomUUID().toString());
    auditRecord.setRunId(context.runId());
    auditRecord.setStepId(context.stepId());
    auditRecord.setToolName(descriptor.name());
    auditRecord.setToolVersion(descriptor.version());
    auditRecord.setRiskLevel(descriptor.riskLevel());
    auditRecord.setStatus(ToolCallStatus.PENDING);
    auditRecord.setIdempotencyKey(idempotencyKey);
    auditRecord.setArgumentsHash(argumentsHash);
    auditRecord.setArgumentsRedactedJson(argumentAuditSummarizer.summarize(declaredInputType, input));
    return auditRecord;
}
```

修改幂等键方法，并删除 `hashInput`：

```java
private static String resolveIdempotencyKey(
        ToolExecutionContext context,
        String toolName,
        String argumentsHash) {
    if (context.idempotencyKey() != null && !context.idempotencyKey().isBlank()) {
        return context.idempotencyKey().trim();
    }
    return toolName + ":" + argumentsHash;
}
```

删除 `ToolExecutor` 中不再使用的以下导入：

```java
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
```

- [x] **步骤 5：运行定向测试并确认全部通过**

运行：

```bash
mvn -q -Dtest=ToolArgumentHasherTests,ToolExecutorTests test
```

预期：规范化哈希单元测试和 ToolExecutor JPA 测试全部通过；自动键两次调用只执行工具一次，持久化调用每次只哈希一次，无 `runId` 调用哈希零次。

- [x] **步骤 6：提交任务 2**

```bash
git add src/main/java/com/example/myllm/harness/application/ToolExecutor.java \
  src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java
git commit -m "安全：接入工具参数规范化哈希"
```

---

### 任务 3：完整验证与范围审查

**文件：**

- 复核：`src/main/java/com/example/myllm/harness/application/ToolArgumentHasher.java`
- 复核：`src/main/java/com/example/myllm/harness/application/ToolExecutor.java`
- 复核：`src/test/java/com/example/myllm/harness/application/ToolArgumentHasherTests.java`
- 复核：`src/test/java/com/example/myllm/harness/application/ToolExecutorTests.java`
- 复核：`docs/superpowers/specs/2026-07-11-harness-tool-argument-hash-design.md`
- 复核：`docs/superpowers/plans/2026-07-11-harness-tool-argument-hash.md`

- [x] **步骤 1：执行完整仓库验证**

```bash
make verify
```

预期：全部 Maven 测试通过，文档链接检查输出 `All doc links OK`。

- [x] **步骤 2：统计测试结果并确认没有跳过或失败**

```bash
perl -ne 'if (/<testsuite /) { /tests="(\d+)"/ and $t += $1; /failures="(\d+)"/ and $f += $1; /errors="(\d+)"/ and $e += $1; /skipped="(\d+)"/ and $s += $1 } END { print "tests=$t failures=$f errors=$e skipped=$s\n" }' \
  target/surefire-reports/TEST-*.xml
```

预期：`failures=0 errors=0 skipped=0`。

- [x] **步骤 3：执行静态安全检查**

```bash
git diff --check
rg -n "String\.valueOf\(input\)|hashInput\(" \
  src/main/java/com/example/myllm/harness/application/ToolExecutor.java
```

预期：`git diff --check` 无输出；旧参数哈希实现无匹配。

- [x] **步骤 4：确认最终变更范围**

```bash
git status --short
git diff --stat 0f2c22b..HEAD
git diff --name-only 0f2c22b..HEAD
git diff --diff-filter=D --name-only 0f2c22b..HEAD
```

预期：最终验收修复范围只包含本计划、Hasher、Executor 和 Hasher 测试；最后一条无输出。不得出现 Parser、
工具输入 Record、实体、DDL、迁移脚本或文件删除。

- [x] **步骤 5：执行最终代码审查**

审查时逐项核对设计文档的等价边界、安全上限、固定失败、单次哈希、临时调用兼容性和禁止范围。严重或重要
问题必须修复并重新运行覆盖测试；次要问题记录在交付说明。

**最终验收修复结果（2026-07-11，基线 `0f2c22b`）：** 最终审查实际发现并已关闭两项 Important 和一项
Minor：

1. Important：原 `writeJsonNode` 在精确类型判断前调用 `isTextual`、`isNumber` 等节点行为，并用可接受子类的
   ObjectNode/ArrayNode 类型匹配；自定义容器还能让 `size` 与实际遍历结果不一致，绕过预分配保护。现改为先
   读取 `node.getClass()`，只允许十二种标准具体节点类，再调用对应节点行为。
2. Important：原 `writeNumber` 用 `instanceof BigInteger/BigDecimal` 接受两个非 final 类的未知子类。现改为
   对 Byte、Short、Integer、Long、BigInteger、Float、Double、BigDecimal 八种具体类执行精确类判断，继续
   固定拒绝非有限 Float、Double。
3. Minor：`ToolExecutor` 执行顺序 Javadoc 漏写风险策略校验后、幂等查询前的“持久化路径参数规范化哈希”。
   文档顺序已补齐，执行代码未改变。

严格 TDD 的 RED 命令 `mvn -q -Dtest=ToolArgumentHasherTests test` 实际得到 22 个测试、6 个失败、0 个错误；
六项失败均为当前实现没有抛出固定 `HarnessDomainException`，分别覆盖自定义 TextNode、IntNode、ObjectNode、
ArrayNode 以及 BigInteger、BigDecimal 子类。最小生产修复后，同一命令 GREEN，随后
`mvn -q -Dtest=ToolArgumentHasherTests,ToolExecutorTests test` 退出码为 0。

最终 `make verify` 退出码为 0，Surefire 汇总为 184 个测试、0 失败、0 错误、0 跳过，文档链接检查输出
`All doc links OK`。`git diff --check` 无输出，旧参数哈希和旧 JsonNode/Number 白名单扫描均无匹配；
`0f2c22b..HEAD` 仅包含本计划、`ToolArgumentHasher`、`ToolExecutor` 和 `ToolArgumentHasherTests`，没有删除文件。
自审确认：自定义子类在顶层和 Map/List/Record 嵌套入口均固定失败，标准节点仍与等价 Java 值产生相同哈希，
异常保持固定中文消息、无 cause、无原始值，Executor 行为由原集成测试保持不变。

- [x] **步骤 6：提交实施计划完成状态**

在本计划中勾选已执行步骤，并将计划与最后一次必要修正一并提交；若代码提交后计划是唯一剩余变更，单独执行：

```bash
git add docs/superpowers/plans/2026-07-11-harness-tool-argument-hash.md
git commit -m "文档：记录工具参数哈希实施结果"
```

最终工作树必须干净。
