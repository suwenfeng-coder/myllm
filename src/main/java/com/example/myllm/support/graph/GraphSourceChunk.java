package com.example.myllm.support.graph;

/**
 * 从 PostgreSQL 读取的构图分片快照。
 *
 * @param fileId 文件唯一标识
 * @param chunkIndex 文件内分片序号
 * @param chunkText 分片正文，仅用于生成短预览，不整段写入 Neo4j
 * @param headingPath 章节路径
 * @param charCount 字符数
 * @param tokenCount Token 估算值
 * @param contentHash 标准化内容哈希
 */
public record GraphSourceChunk(
        String fileId,
        int chunkIndex,
        String chunkText,
        String headingPath,
        int charCount,
        Integer tokenCount,
        String contentHash) {
}
