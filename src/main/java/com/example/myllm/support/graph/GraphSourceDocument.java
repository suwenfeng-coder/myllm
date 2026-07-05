package com.example.myllm.support.graph;

import java.util.List;

/**
 * 构建 Neo4j 结构图所需的文档快照，正文主数据仍保存在 PostgreSQL。
 *
 * @param fileId 文件唯一标识
 * @param fileName 原始文件名
 * @param contentType 文件类型
 * @param cleanerVersion 清洗规则版本
 * @param chunkStrategy 实际分块策略
 * @param chunks 按 chunkIndex 排序的分片
 */
public record GraphSourceDocument(
        String fileId,
        String fileName,
        String contentType,
        String cleanerVersion,
        String chunkStrategy,
        List<GraphSourceChunk> chunks) {

    public GraphSourceDocument {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
