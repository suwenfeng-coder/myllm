package com.example.myllm.support.chunking;

import com.example.myllm.support.document.CleanedDocument;

/**
 * 文档分块策略的统一扩展接口。
 *
 * <p>实现类只负责从清理后的文档产生文本块，不负责生成最终入库向量。</p>
 */
public interface DocumentChunker {

    /**
     * @return 当前实现所对应的策略类型
     */
    ChunkStrategy strategy();

    /**
     * 对清理后的文档执行分块。
     *
     * @param document 已通过质量门禁的清理结果
     * @return 分块结果及实际使用的策略
     */
    ChunkingResult chunk(CleanedDocument document);
}
