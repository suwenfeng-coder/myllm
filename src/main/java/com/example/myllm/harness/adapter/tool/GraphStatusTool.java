package com.example.myllm.harness.adapter.tool;

import com.example.myllm.dto.GraphIndexTaskResponse;
import com.example.myllm.harness.adapter.tool.model.GraphStatusInput;
import com.example.myllm.harness.adapter.tool.model.GraphStatusOutput;
import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.port.HarnessTool;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolIds;
import com.example.myllm.harness.port.ToolResult;
import com.example.myllm.service.GraphIndexTaskService;
import org.springframework.stereotype.Component;

/** 查询文件最近一笔 Neo4j 构图任务状态。 */
@Component
public class GraphStatusTool implements HarnessTool<GraphStatusInput, GraphStatusOutput> {

    private final GraphIndexTaskService graphIndexTaskService;

    public GraphStatusTool(GraphIndexTaskService graphIndexTaskService) {
        this.graphIndexTaskService = graphIndexTaskService;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                ToolIds.GRAPH_STATUS,
                "1",
                "查询 Neo4j 构图/删除任务状态",
                ToolRisk.READ_ONLY,
                10_000,
                true,
                false,
                16_384);
    }

    @Override
    public Class<GraphStatusInput> inputType() {
        return GraphStatusInput.class;
    }

    @Override
    public ToolResult<GraphStatusOutput> execute(ToolExecutionContext context, GraphStatusInput input) {
        long start = System.nanoTime();
        if (input == null || input.fileId() == null || input.fileId().isBlank()) {
            return ToolResult.failed("INVALID_INPUT", "fileId 不能为空", elapsedMs(start));
        }
        try {
            return graphIndexTaskService.findLatest(input.fileId().trim())
                    .map(task -> ToolResult.ok(toOutput(task, true), preview(task), elapsedMs(start)))
                    .orElseGet(() -> ToolResult.ok(
                            new GraphStatusOutput(input.fileId().trim(), false, null, null, 0, 0, 0, null, null),
                            "not_found",
                            elapsedMs(start)));
        } catch (Exception e) {
            return ToolResult.failed("TOOL_EXECUTION_FAILED", e.getMessage(), elapsedMs(start));
        }
    }

    private static GraphStatusOutput toOutput(GraphIndexTaskResponse task, boolean found) {
        return new GraphStatusOutput(
                task.fileId(),
                found,
                task.operation(),
                task.status(),
                task.attemptCount(),
                task.nodeCount(),
                task.relationshipCount(),
                task.errorMessage(),
                task.finishedAt());
    }

    private static String preview(GraphIndexTaskResponse task) {
        return task.fileId() + " " + task.operation() + " " + task.status();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
