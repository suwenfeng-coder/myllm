-- file_embeddings 的生产级 BM25 索引。
-- 运行前提：PostgreSQL 已安装 ParadeDB pg_search 扩展；普通 pgvector 镜像无法执行本脚本。
-- 建议先在新实例完成数据迁移和检索验收，再切换应用连接，避免在线创建大索引。

CREATE EXTENSION IF NOT EXISTS pg_search;

-- ParadeDB 每张表只能有一个 BM25 索引；key_field 必须是唯一且非空的 id。
-- 中文标题和正文均使用 Jieba 分词；file_id/chunk_index 用于范围过滤及结果定位。
CREATE INDEX IF NOT EXISTS file_embeddings_bm25_idx
    ON file_embeddings
    USING bm25 (
        id,
        (file_name::pdb.jieba),
        (chunk_text::pdb.jieba),
        file_id,
        chunk_index
    )
    WITH (key_field = 'id');

-- 基础验收：应返回 true、true。
SELECT EXISTS (
    SELECT 1 FROM pg_extension WHERE extname = 'pg_search'
) AS pg_search_installed;

SELECT to_regclass('file_embeddings_bm25_idx') IS NOT NULL AS bm25_index_created;

