package com.example.myllm.support.graph;

import java.util.List;

/**
 * 校验后的实体关系抽取结果。
 *
 * @param sourceEntityKey 源实体键
 * @param targetEntityKey 目标实体键
 * @param relationType 关系类型
 * @param confidence 置信度
 * @param evidence 原文短证据
 * @param evidenceChunkIds 支撑该关系的分片 ID
 */
public record GraphExtractionRelation(
        String sourceEntityKey,
        String targetEntityKey,
        GraphRelationType relationType,
        double confidence,
        String evidence,
        List<String> evidenceChunkIds) {

    public GraphExtractionRelation {
        evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
    }
}
