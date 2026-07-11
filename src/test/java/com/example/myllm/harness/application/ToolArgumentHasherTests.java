package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private enum Status {
        READY
    }

    private record SearchInput(String query, List<String> fileIds) {
    }

    private record RecordWithMap(Map<?, ?> values) {
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
}
