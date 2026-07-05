package com.example.myllm.service;

import com.example.myllm.support.chunking.ChunkSizeLimiter;
import com.example.myllm.support.chunking.ChunkStrategy;
import com.example.myllm.support.chunking.ChunkWhitespaceNormalizer;
import com.example.myllm.support.chunking.ChunkingResult;
import com.example.myllm.support.chunking.DocumentChunk;
import com.example.myllm.support.chunking.DocumentChunkFactory;
import com.example.myllm.support.chunking.DocumentChunker;
import com.example.myllm.support.chunking.RecursiveDocumentChunker;
import com.example.myllm.support.document.CleanedDocument;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 分块策略注册与调度服务。
 *
 * <p>Spring 注入全部 {@link DocumentChunker} 实现后按策略类型建立只读映射，
 * 上传流程只需提供策略名称即可执行对应实现。</p>
 */
@Service
public class DocumentChunkingService {

    private final Map<ChunkStrategy, DocumentChunker> chunkers;
    private final RecursiveDocumentChunker recursiveDocumentChunker;
    private final int hardMaxChunkSize;

    public DocumentChunkingService(
            List<DocumentChunker> chunkers,
            RecursiveDocumentChunker recursiveDocumentChunker,
            @Value("${rag.chunking.document.max-size:1400}") int hardMaxChunkSize) {
        Map<ChunkStrategy, DocumentChunker> resolved = new EnumMap<>(ChunkStrategy.class);
        for (DocumentChunker chunker : chunkers) {
            if (resolved.put(chunker.strategy(), chunker) != null) {
                throw new IllegalStateException("存在重复的分块策略实现: " + chunker.strategy());
            }
        }
        this.chunkers = Map.copyOf(resolved);
        this.recursiveDocumentChunker = recursiveDocumentChunker;
        this.hardMaxChunkSize = Math.max(200, hardMaxChunkSize);
    }

    /**
     * 解析策略参数并执行对应分块器。
     *
     * @param document 已完成清理和质量校验的文档
     * @param requestedStrategy HTTP 接口传入的策略名称
     * @return 非空分块结果
     * @throws IllegalArgumentException 策略非法或未产生有效文本块时抛出
     */
    public ChunkingResult chunk(CleanedDocument document, String requestedStrategy) {
        ChunkStrategy strategy = ChunkStrategy.from(requestedStrategy);
        DocumentChunker chunker = chunkers.get(strategy);
        if (chunker == null) {
            throw new IllegalStateException("分块策略未注册: " + strategy.apiValue());
        }
        ChunkingResult result = chunker.chunk(document);
        List<DocumentChunk> normalizedChunks = new ArrayList<>();
        for (DocumentChunk chunk : result.chunks()) {
            String normalized = ChunkWhitespaceNormalizer.normalize(chunk.embeddingContent());
            if (normalized.isBlank()) {
                continue;
            }
            normalizedChunks.add(DocumentChunkFactory.of(
                    normalizedChunks.size(),
                    normalized,
                    chunk.headingPath(),
                    chunk.startSourceIndex(),
                    chunk.endSourceIndex(),
                    chunk.metadata()));
        }
        List<DocumentChunk> limitedChunks = ChunkSizeLimiter.enforceMaxSize(
                normalizedChunks, hardMaxChunkSize, recursiveDocumentChunker);
        if (limitedChunks.isEmpty()) {
            throw new IllegalArgumentException("文件清理后无法生成有效分块");
        }
        return new ChunkingResult(result.requestedStrategy(), result.appliedStrategy(), limitedChunks);
    }
}
