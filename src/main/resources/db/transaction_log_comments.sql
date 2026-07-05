-- transaction_log 表及字段中文注释（已有库执行一次即可）

ALTER TABLE transaction_log COMMENT = '大模型对话交易日志表';

ALTER TABLE transaction_log
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '请求时间',
    MODIFY COLUMN provider VARCHAR(32) NOT NULL COMMENT '模型提供商',
    MODIFY COLUMN model VARCHAR(128) NOT NULL COMMENT '调用的模型名称',
    MODIFY COLUMN duration_ms BIGINT NOT NULL COMMENT '端到端耗时（毫秒）',
    MODIFY COLUMN input_content LONGTEXT NOT NULL COMMENT '用户输入内容',
    MODIFY COLUMN output_content LONGTEXT NULL COMMENT '模型输出内容',
    MODIFY COLUMN system_prompt LONGTEXT NULL COMMENT '系统提示词',
    MODIFY COLUMN input_tokens BIGINT NULL COMMENT '输入Token数',
    MODIFY COLUMN output_tokens BIGINT NULL COMMENT '输出Token数',
    MODIFY COLUMN total_tokens BIGINT NULL COMMENT '总Token消耗量',
    MODIFY COLUMN inference_speed_tps DOUBLE NULL COMMENT '推理速度（tokens/秒）',
    MODIFY COLUMN prompt_eval_duration_ms BIGINT NULL COMMENT 'Prompt编码耗时（毫秒）',
    MODIFY COLUMN eval_duration_ms BIGINT NULL COMMENT '模型生成耗时（毫秒）',
    MODIFY COLUMN load_duration_ms BIGINT NULL COMMENT '模型加载耗时（毫秒）',
    MODIFY COLUMN model_total_duration_ms BIGINT NULL COMMENT 'Ollama报告的总推理耗时（毫秒）',
    MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT '请求状态（SUCCESS/FAILED）',
    MODIFY COLUMN error_message TEXT NULL COMMENT '失败错误信息',
    MODIFY COLUMN request_type VARCHAR(16) NOT NULL COMMENT '请求类型（POST/GET）';
