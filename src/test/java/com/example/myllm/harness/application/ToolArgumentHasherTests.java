package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
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
    @DisplayName("Java 数组和结构化 JsonNode 与等价集合生成相同哈希")
    void hashesEquivalentArraysAndStructuredJsonNodesTheSame() {
        Object[] objectArray = {"a", 1, true};
        assertEquals(hasher.hash(List.of("a", 1, true)), hasher.hash(objectArray));

        int[] primitiveArray = {1, 2, 3};
        assertEquals(hasher.hash(List.of(1, 2, 3)), hasher.hash(primitiveArray));

        ObjectNode objectNode = JsonNodeFactory.instance.objectNode();
        objectNode.put("z", 2);
        objectNode.put("a", "x");
        Map<String, Object> equivalentMap = new LinkedHashMap<>();
        equivalentMap.put("a", "x");
        equivalentMap.put("z", 2);
        assertEquals(hasher.hash(equivalentMap), hasher.hash(objectNode));

        ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
        arrayNode.add("a");
        arrayNode.add(1);
        arrayNode.add(true);
        assertEquals(hasher.hash(List.of("a", 1, true)), hasher.hash(arrayNode));
    }

    @Test
    @DisplayName("合法标量 JsonNode 与对应 Java 标量生成相同哈希")
    void hashesEquivalentScalarJsonNodesTheSame() {
        assertEquals(
                hasher.hash("文本"),
                hasher.hash(JsonNodeFactory.instance.textNode("文本")));
        assertEquals(
                hasher.hash(true),
                hasher.hash(JsonNodeFactory.instance.booleanNode(true)));
        assertEquals(
                hasher.hash(42),
                hasher.hash(JsonNodeFactory.instance.numberNode(42)));
        assertEquals(
                hasher.hash(new BigDecimal("1.5")),
                hasher.hash(JsonNodeFactory.instance.numberNode(new BigDecimal("1.5"))));
        assertEquals(
                hasher.hash(null),
                hasher.hash(JsonNodeFactory.instance.nullNode()));
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
    @DisplayName("拒绝非 Set 的其他 Iterable")
    void rejectsOtherIterableWithFixedFailure() {
        assertFixedFailure(new ArrayDeque<>(List.of("a", "b")));
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
    @DisplayName("深度 32 成功且深度 33 失败")
    void enforcesTheExactDepthBoundary() {
        assertSuccessfulHash(nestedListsWithLeafDepth(32));
        assertFixedFailure(nestedListsWithLeafDepth(33));
    }

    @Test
    @DisplayName("总节点 10000 成功且 10001 失败")
    void enforcesTheExactNodeBoundary() {
        // 根 List 本身占一个节点，其余节点均为 null 子节点。
        List<Object> exactlyTenThousandNodes = Collections.nCopies(9_999, null);
        List<Object> tenThousandAndOneNodes = Collections.nCopies(10_000, null);
        assertEquals(10_000, 1 + exactlyTenThousandNodes.size());
        assertEquals(10_001, 1 + tenThousandAndOneNodes.size());

        assertSuccessfulHash(exactlyTenThousandNodes);
        assertFixedFailure(tenThousandAndOneNodes);
    }

    @Test
    @DisplayName("规范 JSON 恰好 1048576 字节成功且多一字节失败")
    void enforcesTheExactJsonByteBoundary() {
        // 纯 ASCII 字符串的规范 JSON 只额外包含首尾两个引号，域前缀不计入此上限。
        String exactlyOneMibJson = "a".repeat(1_048_574);
        String oneByteOverOneMibJson = "a".repeat(1_048_575);
        assertEquals(
                1_048_576,
                exactlyOneMibJson.getBytes(StandardCharsets.UTF_8).length + 2);
        assertEquals(
                1_048_577,
                oneByteOverOneMibJson.getBytes(StandardCharsets.UTF_8).length + 2);

        assertSuccessfulHash(exactlyOneMibJson);
        assertFixedFailure(oneByteOverOneMibJson);
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

    private void assertSuccessfulHash(Object input) {
        assertEquals(64, hasher.hash(input).length());
    }

    private Object nestedListsWithLeafDepth(int depth) {
        // 叶子位于根时深度为 0；每包一层 List，叶子深度增加 1。
        Object value = "leaf";
        for (int index = 0; index < depth; index++) {
            value = List.of(value);
        }
        return value;
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
