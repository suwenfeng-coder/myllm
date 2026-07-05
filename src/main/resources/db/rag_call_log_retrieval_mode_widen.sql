-- 扩展 rag_call_log.retrieval_mode，容纳 Graph Shadow 等较长模式名。
-- 例如 HYBRID_GRAPH_SHADOW (19)、VECTOR_GRAPH_SHADOW (19)。
-- 已有库执行一次即可；ddl-auto=update 也会随实体同步。

ALTER TABLE rag_call_log
    MODIFY COLUMN retrieval_mode VARCHAR(32) NULL
        COMMENT '实际检索模式（VECTOR/HYBRID/HYBRID_GRAPH_SHADOW 等）';
