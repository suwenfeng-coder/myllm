-- file_embeddings 元数据字段迁移（应用启动时也会通过 ensureVectorTableExists 自动执行）
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS content_type VARCHAR(16);
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS file_size_bytes BIGINT;
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS embedding_content TEXT;
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS heading_path TEXT;
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS char_count INT;
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS token_count INT;
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS content_hash CHAR(64);
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS start_source_index INT;
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS end_source_index INT;
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS cleaner_version VARCHAR(64);
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS chunk_strategy_version VARCHAR(64);
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(64);
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS embedding_model_version VARCHAR(32);
ALTER TABLE file_embeddings ADD COLUMN IF NOT EXISTS metadata JSONB DEFAULT '{}'::jsonb;

UPDATE file_embeddings
SET embedding_content = chunk_text
WHERE embedding_content IS NULL AND chunk_text IS NOT NULL;

UPDATE file_embeddings
SET char_count = char_length(chunk_text)
WHERE char_count IS NULL AND chunk_text IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_file_embeddings_file_chunk
    ON file_embeddings (file_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_file_embeddings_file_id ON file_embeddings (file_id);
CREATE INDEX IF NOT EXISTS idx_file_embeddings_content_hash ON file_embeddings (content_hash);
