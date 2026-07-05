package com.example.myllm.support.chunking;

import com.example.myllm.support.document.CleanedDocument;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 递归边界分块器。
 *
 * <p>优先在语义更完整的自然边界处断开；找不到合适边界时才按最大长度硬切，
 * 并通过 overlap 保留相邻块上下文。</p>
 */
@Component
public class RecursiveDocumentChunker implements DocumentChunker {

    private final int maxSize;
    private final int minSize;
    private final int overlap;
    private final boolean useTokenLimits;
    private final int maxTokens;
    private final int minTokens;
    private final int overlapTokens;

    public RecursiveDocumentChunker(
            @Value("${rag.chunking.max-size:1200}") int maxSize,
            @Value("${rag.chunking.min-size:200}") int minSize,
            @Value("${rag.chunking.overlap:120}") int overlap,
            @Value("${rag.chunking.use-token-limits:true}") boolean useTokenLimits,
            @Value("${rag.chunking.max-tokens:600}") int maxTokens,
            @Value("${rag.chunking.min-tokens:80}") int minTokens,
            @Value("${rag.chunking.overlap-tokens:75}") int overlapTokens) {
        this.maxSize = maxSize;
        this.minSize = minSize;
        this.overlap = overlap;
        this.useTokenLimits = useTokenLimits;
        this.maxTokens = maxTokens;
        this.minTokens = minTokens;
        this.overlapTokens = overlapTokens;
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.RECURSIVE;
    }

    /**
     * @param document 清理后的文档
     * @return 递归边界分块结果
     */
    @Override
    public ChunkingResult chunk(CleanedDocument document) {
        return result(strategy(), split(document.content()));
    }

    ChunkingResult result(ChunkStrategy requested, List<String> chunks) {
        return new ChunkingResult(requested, strategy(), DocumentChunkFactory.fromPlainTexts(chunks));
    }

    List<String> split(String text) {
        return useTokenLimits
                ? ChunkingSupport.recursiveByTokens(text, maxTokens, minTokens, overlapTokens)
                : ChunkingSupport.recursive(text, maxSize, minSize, overlap);
    }

    List<DocumentChunk> splitToChunks(String text) {
        return DocumentChunkFactory.fromPlainTexts(split(text));
    }
}
