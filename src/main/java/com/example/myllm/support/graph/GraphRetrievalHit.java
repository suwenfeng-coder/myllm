package com.example.myllm.support.graph;

import java.util.List;

/**
 * Neo4j 图召回命中的分片引用，正文需回源 PostgreSQL 读取。
 *
 * @param fileId 文件 ID
 * @param chunkIndex 分片序号
 * @param graphScore 图通道排序分数（0~1）
 * @param matchedEntities 命中的实体名称
 * @param evidencePath 证据路径简述，如 {@code Entity:呆账核销->Chunk#7}
 */
public record GraphRetrievalHit(
        String fileId,
        int chunkIndex,
        double graphScore,
        List<String> matchedEntities,
        String evidencePath) {

    public GraphRetrievalHit {
        matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
    }
}
