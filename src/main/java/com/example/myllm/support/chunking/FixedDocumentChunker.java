package com.example.myllm.support.chunking;

import com.example.myllm.support.document.CleanedDocument;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 固定大小分块器，用于兼容原有按字符数直接切分的逻辑。
 */
@Component
public class FixedDocumentChunker implements DocumentChunker {

    private final int chunkSize;
    private final boolean useTokenLimits;
    private final int maxTokens;
    private final int overlapTokens;

    public FixedDocumentChunker(
            @Value("${rag.chunking.fixed-size:${vector.chunk-size:1000}}") int chunkSize,
            @Value("${rag.chunking.use-token-limits:true}") boolean useTokenLimits,
            @Value("${rag.chunking.max-tokens:600}") int maxTokens,
            @Value("${rag.chunking.overlap-tokens:75}") int overlapTokens) {
        this.chunkSize = chunkSize;
        this.useTokenLimits = useTokenLimits;
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.FIXED;
    }

    /**
     * @param document 清理后的文档
     * @return 固定窗口分块结果
     */
    @Override
    public ChunkingResult chunk(CleanedDocument document) {
        List<String> chunks = useTokenLimits
                ? ChunkingSupport.fixedByTokens(document.content(), maxTokens, overlapTokens)
                : ChunkingSupport.fixed(document.content(), chunkSize);
        return new ChunkingResult(
                strategy(),
                strategy(),
                DocumentChunkFactory.fromPlainTexts(chunks));
    }
}
