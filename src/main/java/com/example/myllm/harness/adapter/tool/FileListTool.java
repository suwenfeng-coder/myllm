package com.example.myllm.harness.adapter.tool;

import com.example.myllm.dto.VectorFileSummary;
import com.example.myllm.harness.adapter.tool.model.FileListItem;
import com.example.myllm.harness.adapter.tool.model.FileListOutput;
import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.port.HarnessTool;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolIds;
import com.example.myllm.harness.port.ToolResult;
import com.example.myllm.service.FileEmbeddingService;
import java.util.List;
import org.springframework.stereotype.Component;

/** 列出已入库向量文件摘要。 */
@Component
public class FileListTool implements HarnessTool<Void, FileListOutput> {

    private final FileEmbeddingService fileEmbeddingService;

    public FileListTool(FileEmbeddingService fileEmbeddingService) {
        this.fileEmbeddingService = fileEmbeddingService;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                ToolIds.FILE_LIST,
                "1",
                "列出知识库已入库文件",
                ToolRisk.READ_ONLY,
                15_000,
                true,
                false,
                65_536);
    }

    @Override
    public Class<Void> inputType() {
        return Void.class;
    }

    @Override
    public ToolResult<FileListOutput> execute(ToolExecutionContext context, Void input) {
        long start = System.nanoTime();
        try {
            List<FileListItem> files = fileEmbeddingService.listFiles().stream()
                    .map(FileListTool::toItem)
                    .toList();
            FileListOutput output = new FileListOutput(files, files.size());
            return ToolResult.ok(output, "files=" + files.size(), elapsedMs(start));
        } catch (Exception e) {
            return ToolResult.failed("TOOL_EXECUTION_FAILED", e.getMessage(), elapsedMs(start));
        }
    }

    private static FileListItem toItem(VectorFileSummary summary) {
        return new FileListItem(
                summary.fileId(),
                summary.fileName(),
                summary.contentType(),
                summary.chunkCount(),
                summary.createdAt());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
