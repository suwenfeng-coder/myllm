package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.port.HarnessTool;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolIds;
import com.example.myllm.harness.port.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryTests {

    @Test
    void registersFourReadOnlyTools() {
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(
                namedTool(ToolIds.KNOWLEDGE_SEARCH, "知识检索"),
                namedTool(ToolIds.FILE_LIST, "文件列表"),
                namedTool(ToolIds.FILE_TASK_STATUS, "上传任务"),
                namedTool(ToolIds.GRAPH_STATUS, "构图状态")));

        assertEquals(4, registry.listDescriptors().size());
        assertTrue(registry.contains(ToolIds.KNOWLEDGE_SEARCH));
        assertTrue(registry.contains(ToolIds.FILE_LIST));
        assertTrue(registry.contains(ToolIds.FILE_TASK_STATUS));
        assertTrue(registry.contains(ToolIds.GRAPH_STATUS));
        registry.listDescriptors().forEach(d -> assertEquals(ToolRisk.READ_ONLY, d.riskLevel()));
    }

    private static HarnessTool<Object, String> namedTool(String name, String description) {
        return new HarnessTool<>() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "1", description, ToolRisk.READ_ONLY, 5000, true, false, 4096);
            }

            @Override
            public Class<Object> inputType() {
                return Object.class;
            }

            @Override
            public ToolResult<String> execute(ToolExecutionContext context, Object input) {
                return ToolResult.ok("ok", "ok", 1);
            }
        };
    }
}
