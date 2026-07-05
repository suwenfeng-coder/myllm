package com.example.myllm.support.graph;

import java.util.List;

/** 单文件实体与关系抽取汇总。 */
public record GraphExtractionResult(
        List<GraphExtractionEntity> entities, List<GraphExtractionRelation> relations) {

    public GraphExtractionResult {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    public static GraphExtractionResult empty() {
        return new GraphExtractionResult(List.of(), List.of());
    }
}
