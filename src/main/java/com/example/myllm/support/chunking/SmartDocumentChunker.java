package com.example.myllm.support.chunking;

import com.example.myllm.support.document.CleanedDocument;
import com.example.myllm.support.document.DocumentBlockType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 根据文档特征自动选择实际分块器的智能路由策略。
 *
 * <ul>
 *   <li>存在标题、表格、代码或多个列表项时使用文档结构分块；</li>
 *   <li>长篇非结构化文本使用语义分块；</li>
 *   <li>其他文本使用递归分块；</li>
 *   <li>语义 embedding 异常时降级为递归分块。</li>
 * </ul>
 */
@Component
public class SmartDocumentChunker implements DocumentChunker {

    private static final Logger log = LoggerFactory.getLogger(SmartDocumentChunker.class);

    private final StructureDocumentChunker structureChunker;
    private final SemanticDocumentChunker semanticChunker;
    private final RecursiveDocumentChunker recursiveChunker;
    private final int semanticMinDocumentSize;

    public SmartDocumentChunker(
            StructureDocumentChunker structureChunker,
            SemanticDocumentChunker semanticChunker,
            RecursiveDocumentChunker recursiveChunker,
            @Value("${rag.chunking.smart.semantic-min-document-size:2400}") int semanticMinDocumentSize) {
        this.structureChunker = structureChunker;
        this.semanticChunker = semanticChunker;
        this.recursiveChunker = recursiveChunker;
        this.semanticMinDocumentSize = Math.max(500, semanticMinDocumentSize);
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.SMART;
    }

    /**
     * 自动路由并保留 requested=smart、applied=实际策略，便于日志审计。
     *
     * @param document 清理后的文档
     * @return 智能路由后的分块结果
     */
    @Override
    public ChunkingResult chunk(CleanedDocument document) {
        if (hasMeaningfulStructure(document)) {
            return asSmart(structureChunker.chunk(document));
        }
        if (document.content().length() >= semanticMinDocumentSize) {
            try {
                return asSmart(semanticChunker.chunk(document));
            } catch (RuntimeException e) {
                log.warn("智能分块的语义策略失败，回退到递归分块: {}", e.getMessage());
            }
        }
        return asSmart(recursiveChunker.chunk(document));
    }

    private ChunkingResult asSmart(ChunkingResult result) {
        return new ChunkingResult(strategy(), result.appliedStrategy(), result.chunks());
    }

    private static boolean hasMeaningfulStructure(CleanedDocument document) {
        long structuralBlocks = document.blocks().stream()
                .filter(block -> block.type() == DocumentBlockType.HEADING
                        || block.type() == DocumentBlockType.TABLE_ROW
                        || block.type() == DocumentBlockType.CODE
                        || block.type() == DocumentBlockType.LIST_ITEM)
                .count();
        boolean hasHeading = document.blocks().stream()
                .anyMatch(block -> block.type() == DocumentBlockType.HEADING);
        return hasHeading || structuralBlocks >= 3;
    }
}
