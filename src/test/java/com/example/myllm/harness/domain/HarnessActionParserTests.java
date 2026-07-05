package com.example.myllm.harness.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HarnessActionParserTests {

    private HarnessActionParser parser;

    @BeforeEach
    void setUp() {
        parser = new HarnessActionParser(new ObjectMapper());
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
        assertTrue(!result.success());
        assertTrue(result.errorMessage().contains("未知 action"));
    }

    @Test
    void rejectsInvalidJson() {
        HarnessActionParser.ParseResult result = parser.parse("not-json");
        assertTrue(!result.success());
    }
}
