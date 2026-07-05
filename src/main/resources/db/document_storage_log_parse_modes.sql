-- document_storage_log 增加多模式解析审计字段（MySQL，已有库执行一次即可）。

ALTER TABLE document_storage_log
    ADD COLUMN requested_parse_mode VARCHAR(16) NULL COMMENT '请求解析模式 auto/local/docling/maker' AFTER parse_mode,
    ADD COLUMN parser_engine VARCHAR(32) NULL COMMENT '解析服务报告的实际引擎' AFTER requested_parse_mode,
    ADD COLUMN attempted_parse_modes VARCHAR(128) NULL COMMENT '自动模式依次尝试的解析器' AFTER parser_engine,
    ADD COLUMN parse_duration_ms BIGINT NULL COMMENT '解析链路总耗时（毫秒）' AFTER attempted_parse_modes,
    ADD COLUMN parse_pages INT NULL COMMENT '解析页数' AFTER parse_duration_ms,
    ADD COLUMN parse_fallback_reason TEXT NULL COMMENT '自动模式或引擎内部降级原因' AFTER parse_pages;

UPDATE document_storage_log
SET requested_parse_mode = COALESCE(requested_parse_mode, parse_mode),
    parser_engine = COALESCE(parser_engine, parse_mode),
    attempted_parse_modes = COALESCE(attempted_parse_modes, parse_mode)
WHERE requested_parse_mode IS NULL
   OR parser_engine IS NULL
   OR attempted_parse_modes IS NULL;
