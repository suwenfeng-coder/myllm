package com.example.myllm.harness.adapter.tool;

import com.example.myllm.dto.UploadTaskStatusResponse;
import com.example.myllm.harness.adapter.tool.model.FileTaskStatusInput;
import com.example.myllm.harness.adapter.tool.model.FileTaskStatusOutput;
import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.port.HarnessTool;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolIds;
import com.example.myllm.harness.port.ToolResult;
import com.example.myllm.service.FileUploadTaskService;
import org.springframework.stereotype.Component;

/** 查询异步上传任务状态。 */
@Component
public class FileTaskStatusTool implements HarnessTool<FileTaskStatusInput, FileTaskStatusOutput> {

    private final FileUploadTaskService fileUploadTaskService;

    public FileTaskStatusTool(FileUploadTaskService fileUploadTaskService) {
        this.fileUploadTaskService = fileUploadTaskService;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                ToolIds.FILE_TASK_STATUS,
                "1",
                "查询文档上传向量化任务状态",
                ToolRisk.READ_ONLY,
                10_000,
                true,
                false,
                16_384);
    }

    @Override
    public Class<FileTaskStatusInput> inputType() {
        return FileTaskStatusInput.class;
    }

    @Override
    public ToolResult<FileTaskStatusOutput> execute(ToolExecutionContext context, FileTaskStatusInput input) {
        long start = System.nanoTime();
        if (input == null || input.taskId() == null || input.taskId().isBlank()) {
            return ToolResult.failed("INVALID_INPUT", "taskId 不能为空", elapsedMs(start));
        }
        try {
            return fileUploadTaskService.findStatus(input.taskId().trim())
                    .map(status -> ToolResult.ok(toOutput(status, true), preview(status), elapsedMs(start)))
                    .orElseGet(() -> ToolResult.ok(
                            new FileTaskStatusOutput(
                                    input.taskId().trim(), null, null, 0, null, null, null, false),
                            "not_found",
                            elapsedMs(start)));
        } catch (Exception e) {
            return ToolResult.failed("TOOL_EXECUTION_FAILED", e.getMessage(), elapsedMs(start));
        }
    }

    private static FileTaskStatusOutput toOutput(UploadTaskStatusResponse status, boolean found) {
        return new FileTaskStatusOutput(
                status.taskId(),
                status.status(),
                status.phase(),
                status.percent(),
                status.message(),
                status.fileId(),
                status.errorMessage(),
                found);
    }

    private static String preview(UploadTaskStatusResponse status) {
        return status.taskId() + " " + status.status() + " " + status.percent() + "%";
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
