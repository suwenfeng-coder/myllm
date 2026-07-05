package com.example.myllm.support.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonPayloadRepairerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesJsonWithTrailingComma() {
        String payload = """
                {
                  "entities": [],
                  "relations": [],
                }
                """;

        assertEquals(0, JsonPayloadRepairer.parseLenient(objectMapper, payload).path("entities").size());
    }

    @Test
    void extractsJsonFromMarkdownFence() {
        String payload = """
                ```json
                {"entities":[{"localId":"e1","name":"测试"}],"relations":[]}
                ```
                """;

        assertEquals("测试", JsonPayloadRepairer.parseLenient(objectMapper, payload)
                .path("entities").get(0).path("name").asText());
    }

    @Test
    void rejectsEmptyPayload() {
        assertThrows(IllegalStateException.class, () -> JsonPayloadRepairer.parseLenient(objectMapper, "  "));
    }
}
