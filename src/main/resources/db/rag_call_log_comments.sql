-- rag_call_log 表及字段中文注释（已有库执行一次即可）

ALTER TABLE rag_call_log COMMENT = 'RAG调用日志表';

ALTER TABLE rag_call_log
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    MODIFY COLUMN transaction_log_id BIGINT NOT NULL COMMENT '关联交易日志ID',
    MODIFY COLUMN rag_enabled BIT(1) NOT NULL COMMENT '是否开启RAG',
    MODIFY COLUMN rag_attempted BIT(1) NOT NULL COMMENT '是否执行RAG检索',
    MODIFY COLUMN rag_applied BIT(1) NOT NULL COMMENT '是否将检索结果用于提示词',
    MODIFY COLUMN retrieved_chunks INT NOT NULL COMMENT '检索命中分片数',
    MODIFY COLUMN selected_file_ids TEXT NULL COMMENT '检索限定文件ID（逗号分隔）',
    MODIFY COLUMN rag_status VARCHAR(24) NOT NULL COMMENT 'RAG状态（DISABLED/SUCCESS/FALLBACK）',
    MODIFY COLUMN rag_error TEXT NULL COMMENT 'RAG错误信息',
    MODIFY COLUMN normalized_query TEXT NULL COMMENT '规范化后的问题',
    MODIFY COLUMN rewritten_query TEXT NULL COMMENT '改写后用于检索的问题',
    MODIFY COLUMN rewrite_strategy VARCHAR(32) NULL COMMENT '问题改写策略（DISABLED/RULE_NORMALIZED/RULE_FILENAME_HINT）',
    MODIFY COLUMN query_intent VARCHAR(24) NULL COMMENT '问题意图（FILENAME_LOOKUP/CONTENT_QA/HYBRID/SKIP_RAG）',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '记录创建时间';
