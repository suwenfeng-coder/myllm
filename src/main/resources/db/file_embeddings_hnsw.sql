-- pgvector HNSW cosine 索引（应用启动时 ensureVectorTableExists 也会自动创建）
CREATE INDEX IF NOT EXISTS idx_file_embeddings_embedding_hnsw
    ON file_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
