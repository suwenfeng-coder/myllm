-- Neo4j 文档构图持久化任务表（MySQL 8）。
-- 应用开发环境可由 spring.jpa.hibernate.ddl-auto=update 创建；生产环境应显式执行本迁移。

CREATE TABLE IF NOT EXISTS document_graph_index_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务主键ID',
    file_id VARCHAR(64) NOT NULL COMMENT '关联向量文件ID',
    file_name VARCHAR(512) NOT NULL COMMENT '原始文件名',
    operation VARCHAR(16) NOT NULL COMMENT '任务操作INDEX或DELETE',
    status VARCHAR(24) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/DEAD/CANCELED',
    extraction_version VARCHAR(64) NOT NULL COMMENT '图结构或知识抽取版本',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已执行次数',
    node_count INT NOT NULL DEFAULT 0 COMMENT '最近成功生成的节点数量',
    relationship_count INT NOT NULL DEFAULT 0 COMMENT '最近成功生成的关系数量',
    error_message TEXT NULL COMMENT '最近失败原因',
    next_retry_at DATETIME(6) NULL COMMENT '下次允许重试时间',
    started_at DATETIME(6) NULL COMMENT '最近开始执行时间',
    finished_at DATETIME(6) NULL COMMENT '最终完成或进入死信时间',
    created_at DATETIME(6) NOT NULL COMMENT '任务创建时间',
    updated_at DATETIME(6) NOT NULL COMMENT '任务更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_graph_task_file_version_operation (file_id, extraction_version, operation),
    KEY idx_graph_task_status_retry (status, next_retry_at),
    KEY idx_graph_task_file_id (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Neo4j文档构图任务表';
