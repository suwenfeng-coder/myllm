package com.example.myllm.support.graph;

import java.util.List;

/**
 * 校验后的实体抽取结果。
 *
 * @param entityKey 全局唯一键，格式 entityType:normalizedName
 * @param name 原始展示名
 * @param normalizedName 归一化名称
 * @param entityType 实体类型
 * @param aliases 别名列表
 * @param confidence 置信度
 * @param chunkIds 提及该实体的分片 ID
 */
public record GraphExtractionEntity(
        String entityKey,
        String name,
        String normalizedName,
        GraphEntityType entityType,
        List<String> aliases,
        double confidence,
        List<String> chunkIds) {

    public GraphExtractionEntity {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
    }
}
