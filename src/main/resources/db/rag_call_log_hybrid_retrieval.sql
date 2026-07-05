-- rag_call_log 增加混合检索可观测字段（MySQL，已有库执行一次即可）。
-- spring.jpa.hibernate.ddl-auto=update 也会自动补列；生产环境建议显式执行并纳入版本管理。

ALTER TABLE rag_call_log
    ADD COLUMN retrieval_mode VARCHAR(16) NULL COMMENT '实际检索模式（VECTOR/HYBRID/SKIPPED）' AFTER query_intent,
    ADD COLUMN dense_hit_count INT NULL COMMENT 'Dense向量召回数量' AFTER retrieval_mode,
    ADD COLUMN bm25_hit_count INT NULL COMMENT 'BM25召回数量' AFTER dense_hit_count,
    ADD COLUMN fused_hit_count INT NULL COMMENT 'RRF融合后的候选数量' AFTER bm25_hit_count,
    ADD COLUMN bm25_duration_ms BIGINT NULL COMMENT 'BM25召回耗时（毫秒）' AFTER fused_hit_count,
    ADD COLUMN rrf_duration_ms BIGINT NULL COMMENT 'RRF融合耗时（毫秒）' AFTER bm25_duration_ms,
    ADD COLUMN bm25_fallback_reason TEXT NULL COMMENT 'BM25不可用时的降级原因' AFTER rrf_duration_ms;

