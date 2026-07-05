package com.example.myllm.support.chunking;

import java.util.List;

/**
 * 一次文档分块的结果。
 *
 * @param requestedStrategy 用户请求的策略
 * @param appliedStrategy 实际执行的策略；智能路由或降级时可能与请求策略不同
 * @param chunks 按原文顺序排列的分块及元数据
 */
public record ChunkingResult(
        ChunkStrategy requestedStrategy,
        ChunkStrategy appliedStrategy,
        List<DocumentChunk> chunks) {

    public ChunkingResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
