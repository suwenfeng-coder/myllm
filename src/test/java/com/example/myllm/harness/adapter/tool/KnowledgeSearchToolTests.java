package com.example.myllm.harness.adapter.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.myllm.harness.adapter.tool.model.KnowledgeSearchInput;
import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolIds;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeSearchToolTests {

    @Test
    void descriptorIsReadOnlyKnowledgeSearch() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(null);
        assertEquals(ToolIds.KNOWLEDGE_SEARCH, tool.descriptor().name());
        assertEquals(ToolRisk.READ_ONLY, tool.descriptor().riskLevel());
    }

    @Test
    void rejectsBlankQueryWithoutCallingService() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(null);
        var result = tool.execute(
                new ToolExecutionContext(null, null, null, Set.of()),
                new KnowledgeSearchInput("  ", List.of()));
        assertFalse(result.success());
        assertEquals("INVALID_INPUT", result.errorCode());
    }
}
