-- rag_call_log 增加问题改写字段（已有库执行一次即可）

ALTER TABLE rag_call_log
    ADD COLUMN normalized_query TEXT NULL COMMENT '规范化后的问题' AFTER rag_error,
    ADD COLUMN rewritten_query TEXT NULL COMMENT '改写后用于检索的问题' AFTER normalized_query,
    ADD COLUMN rewrite_strategy VARCHAR(32) NULL COMMENT '问题改写策略（DISABLED/RULE_NORMALIZED/RULE_FILENAME_HINT）' AFTER rewritten_query,
    ADD COLUMN query_intent VARCHAR(24) NULL COMMENT '问题意图（FILENAME_LOOKUP/CONTENT_QA/HYBRID/SKIP_RAG）' AFTER rewrite_strategy;
