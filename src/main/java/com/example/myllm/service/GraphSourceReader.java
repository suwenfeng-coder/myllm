package com.example.myllm.service;

import com.example.myllm.support.graph.GraphSourceChunk;
import com.example.myllm.support.graph.GraphSourceDocument;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 从 PostgreSQL 向量表读取结构图源数据。
 *
 * <p>Neo4j 仅保存分片引用和短预览，因此每次重建都以 PostgreSQL 当前数据为准。</p>
 */
@Service
public class GraphSourceReader {

    private static final Pattern SQL_IDENTIFIER =
            Pattern.compile("[\\w&&[^\\d]]\\w*(?:\\.[\\w&&[^\\d]]\\w*)?");

    private final JdbcTemplate vectorJdbcTemplate;
    private final String tableName;

    public GraphSourceReader(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            @Value("${vector.table-name:file_embeddings}") String tableName) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        if (tableName == null || !SQL_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("vector.table-name 不是合法 SQL 标识符");
        }
        this.tableName = tableName;
    }

    /**
     * 按文件读取全部分片；文件不存在或尚无完整分片时返回空。
     *
     * @param fileId 文件唯一标识
     * @return 构图文档快照
     */
    @SuppressWarnings("java:S2077") // tableName is accepted only after strict SQL identifier validation.
    public Optional<GraphSourceDocument> findByFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT file_id, file_name, content_type, cleaner_version,"
                + " applied_chunk_strategy, chunk_index, chunk_text, heading_path,"
                + " COALESCE(char_count, char_length(chunk_text), 0) AS char_count,"
                + " token_count, content_hash"
                + " FROM " + tableName
                + " WHERE file_id = ? ORDER BY chunk_index";
        List<SourceRow> rows = vectorJdbcTemplate.query( // NOSONAR: table name is strictly validated
                sql,
                (rs, rowNum) -> new SourceRow(
                        rs.getString("file_id"),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getString("cleaner_version"),
                        rs.getString("applied_chunk_strategy"),
                        rs.getInt("chunk_index"),
                        rs.getString("chunk_text"),
                        rs.getString("heading_path"),
                        rs.getInt("char_count"),
                        (Integer) rs.getObject("token_count"),
                        rs.getString("content_hash")),
                fileId.trim());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SourceRow first = rows.get(0);
        List<GraphSourceChunk> chunks = rows.stream()
                .map(row -> new GraphSourceChunk(
                        row.fileId(), row.chunkIndex(), row.chunkText(), row.headingPath(),
                        row.charCount(), row.tokenCount(), row.contentHash()))
                .toList();
        return Optional.of(new GraphSourceDocument(
                first.fileId(), first.fileName(), first.contentType(), first.cleanerVersion(),
                first.chunkStrategy(), chunks));
    }

    private record SourceRow(
            String fileId,
            String fileName,
            String contentType,
            String cleanerVersion,
            String chunkStrategy,
            int chunkIndex,
            String chunkText,
            String headingPath,
            int charCount,
            Integer tokenCount,
            String contentHash) {
    }
}
