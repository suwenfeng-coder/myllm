package com.example.myllm.support.graph;

/**
 * 单个文件结构图写入结果。
 *
 * @param nodeCount 本次写入的节点总数（含结构节点与实体节点）
 * @param relationshipCount 本次写入的关系总数（含结构关系、MENTIONS 与 RELATED_TO）
 * @param entityCount 实体节点数
 * @param mentionCount MENTIONS 关系数
 * @param relationCount RELATED_TO 关系数
 */
public record GraphIndexingResult(
        int nodeCount,
        int relationshipCount,
        int entityCount,
        int mentionCount,
        int relationCount) {

    public GraphIndexingResult(int nodeCount, int relationshipCount) {
        this(nodeCount, relationshipCount, 0, 0, 0);
    }
}
