package com.example.myllm.service;

import com.example.myllm.support.graph.GraphExtractionResult;
import com.example.myllm.support.graph.GraphSourceDocument;

/** 从文档分片中抽取实体与关系的扩展点。 */
public interface GraphKnowledgeExtractor {

    /**
     * 对单个文件执行实体和关系抽取。
     *
     * @param document PostgreSQL 构图快照
     * @return 校验后的实体图数据；失败时抛出异常由构图任务重试
     */
    GraphExtractionResult extract(GraphSourceDocument document);
}
