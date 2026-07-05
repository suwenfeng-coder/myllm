package com.example.myllm.harness.adapter.tool;

import com.example.myllm.dto.VectorChunkResult;
import com.example.myllm.harness.adapter.tool.model.KnowledgeSearchCitation;
import com.example.myllm.harness.adapter.tool.model.KnowledgeSearchInput;
import com.example.myllm.harness.adapter.tool.model.KnowledgeSearchOutput;
import com.example.myllm.harness.domain.ToolRisk;
import com.example.myllm.harness.port.HarnessTool;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolExecutionContext;
import com.example.myllm.harness.port.ToolIds;
import com.example.myllm.harness.port.ToolResult;
import com.example.myllm.service.RagRetrievalService;
import com.example.myllm.support.retrieval.QueryRewriteResult;
import java.util.List;
import org.springframework.stereotype.Component;

/** 只读 RAG 检索工具，委托 {@link RagRetrievalService}。 */
@Component
public class KnowledgeSearchTool implements HarnessTool<KnowledgeSearchInput, KnowledgeSearchOutput> {

    private static final int SNIPPET_LEN = 200;

    private final RagRetrievalService ragRetrievalService;

    public KnowledgeSearchTool(RagRetrievalService ragRetrievalService) {
        this.ragRetrievalService = ragRetrievalService;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                ToolIds.KNOWLEDGE_SEARCH,
                "1",
                "混合检索知识库分片（Dense+BM25+可选Graph诊断）",
                ToolRisk.READ_ONLY,
                30_000,
                true,
                false,
                65_536);
    }

    @Override
    public Class<KnowledgeSearchInput> inputType() {
        return KnowledgeSearchInput.class;
    }

    @Override
    public ToolResult<KnowledgeSearchOutput> execute(ToolExecutionContext context, KnowledgeSearchInput input) {
        long start = System.nanoTime();
        if (input == null || input.query() == null || input.query().isBlank()) {
            return ToolResult.failed("INVALID_INPUT", "query 不能为空", elapsedMs(start));
        }
        try {
            List<String> fileIds = input.fileIds() == null ? List.of() : input.fileIds();
            RagRetrievalService.RetrievalResult result = ragRetrievalService.retrieve(input.query().trim(), fileIds);
            KnowledgeSearchOutput output = toOutput(result);
            return ToolResult.ok(output, preview(output), elapsedMs(start));
        } catch (Exception e) {
            return ToolResult.failed("TOOL_EXECUTION_FAILED", e.getMessage(), elapsedMs(start));
        }
    }

    private static KnowledgeSearchOutput toOutput(RagRetrievalService.RetrievalResult result) {
        QueryRewriteResult rewrite = result.rewrite();
        RagRetrievalService.RetrievalDiagnostics diag = result.diagnostics();
        List<KnowledgeSearchCitation> citations = result.acceptedHits().stream()
                .map(KnowledgeSearchTool::toCitation)
                .toList();
        return new KnowledgeSearchOutput(
                rewrite == null ? null : rewrite.originalQuery(),
                rewrite == null ? null : rewrite.retrievalQuery(),
                rewrite == null ? null : rewrite.intent().name(),
                diag == null ? null : diag.retrievalMode(),
                result.rawHits().size(),
                result.acceptedHits().size(),
                result.effectiveFileIds(),
                diag == null ? null : diag.bm25FallbackReason(),
                diag == null ? null : diag.graphFallbackReason(),
                citations);
    }

    private static KnowledgeSearchCitation toCitation(VectorChunkResult chunk) {
        String text = chunk.chunkText();
        String snippet = text == null ? "" : (text.length() <= SNIPPET_LEN ? text : text.substring(0, SNIPPET_LEN) + "...");
        return new KnowledgeSearchCitation(
                chunk.fileId(),
                chunk.fileName(),
                chunk.chunkIndex(),
                chunk.similarity(),
                chunk.headingPath(),
                snippet);
    }

    private static String preview(KnowledgeSearchOutput output) {
        return "mode=" + output.retrievalMode() + " accepted=" + output.acceptedHitCount();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
