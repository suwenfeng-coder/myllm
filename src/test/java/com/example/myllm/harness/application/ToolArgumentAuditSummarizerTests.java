package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.adapter.tool.model.KnowledgeSearchInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolArgumentAuditSummarizerTests {

    private ObjectMapper objectMapper;
    private ToolArgumentAuditSummarizer summarizer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        summarizer = new ToolArgumentAuditSummarizer(objectMapper);
    }

    @Test
    void summarizesConcreteInputWithoutPersistingValues() throws Exception {
        String query = "机密查询原文";
        String fileId = "file-secret-001";

        String json = summarizer.summarize(
                KnowledgeSearchInput.class,
                Map.of("query", query, "fileIds", List.of(fileId, "file-secret-002")));

        JsonNode root = objectMapper.readTree(json);
        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("KnowledgeSearchInput", root.path("inputType").asText());
        assertEquals("object", root.at("/summary/type").asText());
        assertEquals(query.length(), root.at("/summary/fields/query/length").asInt());
        assertEquals(2, root.at("/summary/fields/fileIds/count").asInt());
        assertEquals("string", root.at("/summary/fields/fileIds/elementTypes/0").asText());
        assertFalse(json.contains(query));
        assertFalse(json.contains(fileId));
    }

    @Test
    void omitsDynamicMapKeysAndValues() throws Exception {
        String json = summarizer.summarize(
                Map.class,
                Map.of("用户输入的机密键", "机密值", "另一个键", 99));

        JsonNode root = objectMapper.readTree(json);
        assertEquals("object", root.at("/summary/type").asText());
        assertEquals(2, root.at("/summary/fieldCount").asInt());
        assertFalse(json.contains("用户输入的机密键"));
        assertFalse(json.contains("机密值"));
        assertFalse(json.contains("99"));
    }

    @Test
    void summarizesScalarArrayRecordAndPlainObjectWithoutValues() throws Exception {
        MixedInput input = new MixedInput(
                "sensitive-text",
                42,
                true,
                AuditStatus.PRIVATE_STATE,
                List.of("array-secret", 7, false, Map.of("dynamic-secret", "hidden")),
                new PlainInput("nested-secret"));

        String json = summarizer.summarize(MixedInput.class, input);

        JsonNode root = objectMapper.readTree(json);
        assertEquals("string", root.at("/summary/fields/text/type").asText());
        assertEquals("number", root.at("/summary/fields/number/type").asText());
        assertEquals("boolean", root.at("/summary/fields/enabled/type").asText());
        assertEquals("string", root.at("/summary/fields/status/type").asText());
        assertEquals("array", root.at("/summary/fields/items/type").asText());
        assertEquals(4, root.at("/summary/fields/items/count").asInt());
        assertEquals(
                List.of("boolean", "number", "object", "string"),
                objectMapper.convertValue(
                        root.at("/summary/fields/items/elementTypes"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("object", root.at("/summary/fields/details/type").asText());
        assertEquals("string", root.at("/summary/fields/details/fields/label/type").asText());
        assertFalse(json.contains("sensitive-text"));
        assertFalse(json.contains("PRIVATE_STATE"));
        assertFalse(json.contains("nested-secret"));
        assertFalse(json.contains("dynamic-secret"));
    }

    @Test
    void returnsNullForAbsentInput() {
        assertNull(summarizer.summarize(Void.class, null));
        assertNull(summarizer.summarize(String.class, null));
    }

    @Test
    void includesEmptyElementTypesForEmptyCollection() throws Exception {
        String json = summarizer.summarize(List.class, List.of());

        JsonNode root = objectMapper.readTree(json);
        assertEquals("array", root.at("/summary/type").asText());
        assertEquals(0, root.at("/summary/count").asInt());
        assertTrue(root.at("/summary/elementTypes").isArray());
        assertTrue(root.at("/summary/elementTypes").isEmpty());
    }

    @Test
    void marksNestedObjectWhenDepthLimitIsReached() throws Exception {
        NestedInput input = nestedInput(6);

        JsonNode root = objectMapper.readTree(summarizer.summarize(NestedInput.class, input));

        assertTrue(root.at(
                        "/summary/fields/child/fields/child/fields/child/fields/child/truncated")
                .asBoolean());
    }

    @Test
    void capsObjectFieldsAtThirtyTwo() throws Exception {
        JsonNode root = objectMapper.readTree(
                summarizer.summarize(ManyFieldsInput.class, new ManyFieldsInput()));

        assertEquals(32, root.at("/summary/fields").size());
        assertTrue(root.at("/summary/truncated").asBoolean());
    }

    @Test
    void returnsSafeFallbackWhenConcreteMapConversionFails() throws Exception {
        String json = summarizer.summarize(NumberInput.class, Map.of("number", "不是数字"));

        assertSafeFallback(json);
    }

    @Test
    void returnsSafeFallbackWhenObjectCannotBeRead() throws Exception {
        String json = assertDoesNotThrow(
                () -> summarizer.summarize(ThrowingInput.class, new ThrowingInput()));

        assertSafeFallback(json);
    }

    @Test
    void returnsSafeFallbackInsteadOfTruncatingOversizedJson() throws Exception {
        ObjectMapper oversizedMapper = new ObjectMapper() {
            @Override
            public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
                return new byte[4097];
            }
        }.findAndRegisterModules();
        ToolArgumentAuditSummarizer oversizedSummarizer =
                new ToolArgumentAuditSummarizer(oversizedMapper);

        String json = oversizedSummarizer.summarize(String.class, "不得持久化的秘密");

        assertSafeFallback(json);
    }

    private void assertSafeFallback(String json) throws Exception {
        assertEquals(
                objectMapper.readTree("{\"schemaVersion\":1,\"summary\":{\"type\":\"unavailable\"}}"),
                objectMapper.readTree(json));
    }

    private static NestedInput nestedInput(int remainingDepth) {
        return remainingDepth == 0
                ? new NestedInput(null)
                : new NestedInput(nestedInput(remainingDepth - 1));
    }

    private enum AuditStatus {
        PRIVATE_STATE
    }

    private record MixedInput(
            String text,
            int number,
            boolean enabled,
            AuditStatus status,
            List<Object> items,
            PlainInput details) {
    }

    private record NestedInput(NestedInput child) {
    }

    private record NumberInput(int number) {
    }

    private static final class ThrowingInput {

        public String getSecret() {
            throw new IllegalStateException("测试读取失败");
        }
    }

    private static final class ManyFieldsInput {

        public String getF01() {
            return "secret";
        }

        public String getF02() {
            return "secret";
        }

        public String getF03() {
            return "secret";
        }

        public String getF04() {
            return "secret";
        }

        public String getF05() {
            return "secret";
        }

        public String getF06() {
            return "secret";
        }

        public String getF07() {
            return "secret";
        }

        public String getF08() {
            return "secret";
        }

        public String getF09() {
            return "secret";
        }

        public String getF10() {
            return "secret";
        }

        public String getF11() {
            return "secret";
        }

        public String getF12() {
            return "secret";
        }

        public String getF13() {
            return "secret";
        }

        public String getF14() {
            return "secret";
        }

        public String getF15() {
            return "secret";
        }

        public String getF16() {
            return "secret";
        }

        public String getF17() {
            return "secret";
        }

        public String getF18() {
            return "secret";
        }

        public String getF19() {
            return "secret";
        }

        public String getF20() {
            return "secret";
        }

        public String getF21() {
            return "secret";
        }

        public String getF22() {
            return "secret";
        }

        public String getF23() {
            return "secret";
        }

        public String getF24() {
            return "secret";
        }

        public String getF25() {
            return "secret";
        }

        public String getF26() {
            return "secret";
        }

        public String getF27() {
            return "secret";
        }

        public String getF28() {
            return "secret";
        }

        public String getF29() {
            return "secret";
        }

        public String getF30() {
            return "secret";
        }

        public String getF31() {
            return "secret";
        }

        public String getF32() {
            return "secret";
        }

        public String getF33() {
            return "secret";
        }
    }

    private static final class PlainInput {

        private final String label;

        private PlainInput(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
}
