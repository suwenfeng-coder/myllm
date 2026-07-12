package com.example.myllm.harness.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.adapter.tool.model.KnowledgeSearchInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HarnessActionParserTests {

    private ObjectMapper objectMapper;
    private HarnessActionParser parser;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        parser = new HarnessActionParser(objectMapper);
    }

    @Test
    void parsesCallToolAction() {
        String json = """
                {"action":"CALL_TOOL","tool":"knowledge.search","arguments":{"query":"年假"},"summary":"检索制度"}
                """;
        HarnessActionParser.ParseResult result = parser.parse(json);
        assertTrue(result.success());
        HarnessAction.CallTool call = assertInstanceOf(HarnessAction.CallTool.class, result.action());
        assertEquals("knowledge.search", call.tool());
        assertEquals("年假", call.arguments().get("query"));
        assertEquals("检索制度", call.summary());
    }

    @Test
    void parsesFileIdsAsListAndBindsKnowledgeSearchInput() {
        String json = """
                {"action":"CALL_TOOL","tool":"knowledge.search","arguments":{
                  "query":"制度依据","fileIds":["file-1","file-2"]
                },"summary":"限定文件检索"}
                """;

        HarnessActionParser.ParseResult result = parser.parse(json);

        assertTrue(result.success());
        HarnessAction.CallTool call = assertInstanceOf(HarnessAction.CallTool.class, result.action());
        List<?> fileIds = assertInstanceOf(List.class, call.arguments().get("fileIds"));
        assertEquals(List.of("file-1", "file-2"), fileIds);
        KnowledgeSearchInput input = objectMapper.convertValue(call.arguments(), KnowledgeSearchInput.class);
        assertEquals("制度依据", input.query());
        assertEquals(List.of("file-1", "file-2"), input.fileIds());
    }

    @Test
    void preservesNestedValuesAndReturnsUnmodifiableCollections() {
        String json = """
                {"action":"CALL_TOOL","tool":"knowledge.search","arguments":{
                  "optional":null,
                  "filters":{
                    "enabled":true,
                    "threshold":1.5,
                    "optionalNested":null,
                    "tags":["A",null,{"rank":2}]
                  }
                },"summary":"测试嵌套参数"}
                """;

        HarnessActionParser.ParseResult result = assertDoesNotThrow(() -> parser.parse(json));

        assertTrue(result.success());
        HarnessAction.CallTool call = assertInstanceOf(HarnessAction.CallTool.class, result.action());
        assertEquals(List.of("optional", "filters"), List.copyOf(call.arguments().keySet()));
        assertTrue(call.arguments().containsKey("optional"));
        assertNull(call.arguments().get("optional"));

        Map<?, ?> filters = assertInstanceOf(Map.class, call.arguments().get("filters"));
        assertEquals(List.of("enabled", "threshold", "optionalNested", "tags"), List.copyOf(filters.keySet()));
        assertEquals(Boolean.TRUE, filters.get("enabled"));
        assertEquals(1.5, filters.get("threshold"));
        assertTrue(filters.containsKey("optionalNested"));
        assertNull(filters.get("optionalNested"));
        List<?> tags = assertInstanceOf(List.class, filters.get("tags"));
        assertEquals("A", tags.get(0));
        assertNull(tags.get(1));
        Map<?, ?> ranked = assertInstanceOf(Map.class, tags.get(2));
        assertEquals(2, ranked.get("rank"));

        assertThrows(UnsupportedOperationException.class, call.arguments()::clear);
        assertThrows(UnsupportedOperationException.class, filters::clear);
        assertThrows(UnsupportedOperationException.class, tags::clear);
        assertThrows(UnsupportedOperationException.class, ranked::clear);
    }

    @Test
    void callToolDefensivelyCopiesTopLevelArgumentsAllowingNullValues() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("first", "value");
        source.put("optional", null);

        HarnessAction.CallTool call = new HarnessAction.CallTool("knowledge.search", source, "测试防御性复制");
        source.put("first", "changed");
        source.put("later", "new");

        assertEquals(List.of("first", "optional"), List.copyOf(call.arguments().keySet()));
        assertEquals("value", call.arguments().get("first"));
        assertTrue(call.arguments().containsKey("optional"));
        assertNull(call.arguments().get("optional"));
        assertFalse(call.arguments().containsKey("later"));
        assertThrows(UnsupportedOperationException.class, call.arguments()::clear);
    }

    @Test
    void keepsMissingNullAndNonObjectArgumentsEmpty() {
        List<String> actions = List.of(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"file.list\"}",
                "{\"action\":\"CALL_TOOL\",\"tool\":\"file.list\",\"arguments\":null}",
                "{\"action\":\"CALL_TOOL\",\"tool\":\"file.list\",\"arguments\":[]}");

        for (String action : actions) {
            HarnessActionParser.ParseResult result = parser.parse(action);
            assertTrue(result.success());
            HarnessAction.CallTool call = assertInstanceOf(HarnessAction.CallTool.class, result.action());
            assertTrue(call.arguments().isEmpty());
        }
    }

    @Test
    void convertsUnsupportedArgumentNodeToFixedFailure() {
        ObjectMapper nonStandardNodeMapper = new ObjectMapper() {
            @Override
            public JsonNode readTree(String content) {
                ObjectNode root = createObjectNode();
                root.put("action", "CALL_TOOL");
                root.put("tool", "knowledge.search");
                root.put("summary", "测试异常边界");
                root.putObject("arguments").set("unsupported", new POJONode(new Object()));
                return root;
            }
        };
        HarnessActionParser nonStandardNodeParser = new HarnessActionParser(nonStandardNodeMapper);

        HarnessActionParser.ParseResult result =
                assertDoesNotThrow(() -> nonStandardNodeParser.parse("ignored"));

        assertFalse(result.success());
        assertEquals("工具参数结构解析失败", result.errorMessage());
    }

    @Test
    void parsesFinalAction() {
        String json = """
                {"action":"FINAL","answer":"结论","citations":[{"sourceId":"f1","excerpt":"片段"}],"summary":"完成"}
                """;
        HarnessActionParser.ParseResult result = parser.parse(json);
        assertTrue(result.success());
        HarnessAction.Final fin = assertInstanceOf(HarnessAction.Final.class, result.action());
        assertEquals("结论", fin.answer());
        assertEquals(1, fin.citations().size());
        assertEquals("f1", fin.citations().get(0).sourceId());
    }

    @Test
    void extractsJsonFromMarkdownFence() {
        String fenced = """
                ```json
                {"action":"FINAL","answer":"ok","citations":[],"summary":""}
                ```
                """;
        HarnessActionParser.ParseResult result = parser.parse(fenced);
        assertTrue(result.success());
        assertInstanceOf(HarnessAction.Final.class, result.action());
    }

    @Test
    void rejectsUnknownAction() {
        HarnessActionParser.ParseResult result = parser.parse("{\"action\":\"DELETE_ALL\"}");
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("未知 action"));
    }

    @Test
    void rejectsInvalidJson() {
        HarnessActionParser.ParseResult result = parser.parse("not-json");
        assertFalse(result.success());
    }
}
