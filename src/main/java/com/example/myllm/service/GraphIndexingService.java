package com.example.myllm.service;

import com.example.myllm.config.GraphProperties;
import com.example.myllm.support.graph.GraphExtractionEntity;
import com.example.myllm.support.graph.GraphExtractionRelation;
import com.example.myllm.support.graph.GraphExtractionResult;
import com.example.myllm.support.graph.GraphIndexingResult;
import com.example.myllm.support.graph.GraphSourceChunk;
import com.example.myllm.support.graph.GraphSourceDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 将 PostgreSQL 文档快照幂等写入 Neo4j 结构图。
 *
 * <p>每次 INDEX 都在一个 Neo4j 事务内按 fileId 替换旧 Section/Chunk，再批量创建当前结构。
 * 失败时整个事务回滚，任务重试不会暴露半份结构图。</p>
 */
@Service
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
public class GraphIndexingService {

    private static final String PARAM_FILE_ID = "fileId";
    private static final String PARAM_CHUNK_ID = "chunkId";

    private static final Logger log = LoggerFactory.getLogger(GraphIndexingService.class);

    private static final String UPSERT_DOCUMENT = "MERGE (d:Document {fileId: $fileId}) "
            + "ON CREATE SET d.createdAt = datetime() "
            + "SET d.fileName = $fileName, d.contentType = $contentType, "
            + "d.cleanerVersion = $cleanerVersion, d.chunkStrategy = $chunkStrategy, "
            + "d.updatedAt = datetime()";
    private static final String DELETE_OLD_CHUNKS =
            "MATCH (c:Chunk {fileId: $fileId}) DETACH DELETE c";
    private static final String DELETE_OLD_SECTIONS =
            "MATCH (s:Section {fileId: $fileId}) DETACH DELETE s";
    private static final String UPSERT_SECTIONS = "UNWIND $rows AS row "
            + "MATCH (d:Document {fileId: $fileId}) "
            + "MERGE (s:Section {sectionId: row.sectionId}) "
            + "SET s.fileId = $fileId, s.headingPath = row.headingPath, s.level = row.level "
            + "MERGE (d)-[:HAS_SECTION]->(s)";
    private static final String UPSERT_CHUNKS = "UNWIND $rows AS row "
            + "MATCH (d:Document {fileId: $fileId}) "
            + "MERGE (c:Chunk {chunkId: row.chunkId}) "
            + "SET c.fileId = $fileId, c.chunkIndex = row.chunkIndex, "
            + "c.contentHash = row.contentHash, c.headingPath = row.headingPath, "
            + "c.charCount = row.charCount, c.tokenCount = row.tokenCount, "
            + "c.preview = row.preview, c.updatedAt = datetime() "
            + "MERGE (d)-[:HAS_CHUNK]->(c)";
    private static final String LINK_SECTION_CHUNKS = "UNWIND $rows AS row "
            + "MATCH (s:Section {sectionId: row.sectionId}) "
            + "MATCH (c:Chunk {chunkId: row.chunkId}) "
            + "MERGE (s)-[:HAS_CHUNK]->(c)";
    private static final String LINK_NEXT_CHUNKS = "UNWIND $rows AS row "
            + "MATCH (current:Chunk {chunkId: row.currentId}) "
            + "MATCH (next:Chunk {chunkId: row.nextId}) "
            + "MERGE (current)-[:NEXT]->(next)";
    private static final String DELETE_OLD_MENTIONS =
            "MATCH (c:Chunk {fileId: $fileId})-[r:MENTIONS]->() DELETE r";
    private static final String DELETE_FILE_RELATIONS =
            "MATCH ()-[r:RELATED_TO]->() "
                    + "WHERE r.evidenceChunkIds IS NOT NULL "
                    + "AND ALL(id IN r.evidenceChunkIds WHERE id STARTS WITH $chunkIdPrefix) "
                    + "DELETE r";
    private static final String UPSERT_ENTITIES = "UNWIND $rows AS row "
            + "MERGE (e:Entity {entityKey: row.entityKey}) "
            + "SET e.name = row.name, e.normalizedName = row.normalizedName, "
            + "e.entityType = row.entityType, e.aliases = row.aliases, e.updatedAt = datetime()";
    private static final String UPSERT_MENTIONS = "UNWIND $rows AS row "
            + "MATCH (c:Chunk {chunkId: row.chunkId}) "
            + "MATCH (e:Entity {entityKey: row.entityKey}) "
            + "MERGE (c)-[r:MENTIONS]->(e) "
            + "SET r.confidence = row.confidence, r.extractionVersion = row.extractionVersion, "
            + "r.updatedAt = datetime()";
    private static final String UPSERT_RELATIONS = "UNWIND $rows AS row "
            + "MATCH (s:Entity {entityKey: row.sourceEntityKey}) "
            + "MATCH (t:Entity {entityKey: row.targetEntityKey}) "
            + "MERGE (s)-[r:RELATED_TO {relationType: row.relationType, extractionVersion: row.extractionVersion}]->(t) "
            + "SET r.confidence = row.confidence, r.evidence = row.evidence, "
            + "r.evidenceChunkIds = row.evidenceChunkIds, r.updatedAt = datetime()";
    private static final String DELETE_DOCUMENT =
            "MATCH (d:Document {fileId: $fileId}) DETACH DELETE d";

    private final Driver driver;
    private final GraphSourceReader sourceReader;
    private final Neo4jAvailabilityService availabilityService;
    private final Neo4jSchemaService schemaService;
    private final GraphProperties properties;
    private final GraphKnowledgeExtractor knowledgeExtractor;

    public GraphIndexingService(
            Driver driver,
            GraphSourceReader sourceReader,
            Neo4jAvailabilityService availabilityService,
            Neo4jSchemaService schemaService,
            GraphProperties properties,
            org.springframework.beans.factory.ObjectProvider<GraphKnowledgeExtractor> knowledgeExtractorProvider) {
        this.driver = driver;
        this.sourceReader = sourceReader;
        this.availabilityService = availabilityService;
        this.schemaService = schemaService;
        this.properties = properties;
        this.knowledgeExtractor = knowledgeExtractorProvider.getIfAvailable();
    }

    /**
     * 从 PostgreSQL 读取指定文件并原子重建结构图。
     *
     * @param fileId 文件唯一标识
     * @return 节点和关系数量
     */
    public GraphIndexingResult indexFile(String fileId) {
        GraphSourceDocument document = sourceReader.findByFileId(fileId)
                .orElseThrow(() -> new IllegalStateException("构图源文件不存在或无分片: " + fileId));
        if (document.chunks().isEmpty()) {
            throw new IllegalStateException("构图源文件没有分片: " + fileId);
        }
        schemaService.ensureSchema();

        List<SectionRow> sections = sections(document);
        List<ChunkRow> chunks = chunks(document);
        List<SectionChunkRow> sectionChunks = chunks.stream()
                .filter(chunk -> chunk.sectionId() != null)
                .map(chunk -> new SectionChunkRow(chunk.sectionId(), chunk.chunkId()))
                .toList();
        List<NextRow> nextRows = nextRows(chunks);
        GraphExtractionResult extraction = extractKnowledge(document);

        try (var session = driver.session(availabilityService.sessionConfig())) {
            session.executeWriteWithoutResult(tx -> {
                tx.run(UPSERT_DOCUMENT, documentParameters(document)).consume();
                tx.run(DELETE_OLD_CHUNKS, Map.of(PARAM_FILE_ID, document.fileId())).consume();
                tx.run(DELETE_OLD_SECTIONS, Map.of(PARAM_FILE_ID, document.fileId())).consume();
                tx.run(DELETE_OLD_MENTIONS, Map.of(PARAM_FILE_ID, document.fileId())).consume();
                tx.run(DELETE_FILE_RELATIONS, Map.of("chunkIdPrefix", document.fileId() + ":")).consume();
                runBatches(tx, UPSERT_SECTIONS, document.fileId(), sections.stream()
                        .map(GraphIndexingService::toMap).toList());
                runBatches(tx, UPSERT_CHUNKS, document.fileId(), chunks.stream()
                        .map(GraphIndexingService::toMap).toList());
                runBatches(tx, LINK_SECTION_CHUNKS, document.fileId(), sectionChunks.stream()
                        .map(GraphIndexingService::toMap).toList());
                runBatches(tx, LINK_NEXT_CHUNKS, document.fileId(), nextRows.stream()
                        .map(GraphIndexingService::toMap).toList());
                writeExtraction(tx, document.fileId(), extraction);
            });
        }

        int structureNodes = 1 + sections.size() + chunks.size();
        int structureRelationships = sections.size() + chunks.size() + sectionChunks.size() + nextRows.size();
        int entityNodes = extraction.entities().size();
        int mentionCount = countMentions(extraction);
        int relationCount = extraction.relations().size();
        int nodeCount = structureNodes + entityNodes;
        int relationshipCount = structureRelationships + mentionCount + relationCount;
        log.info("Neo4j 结构图写入完成 fileId={} sections={} chunks={} entities={} mentions={} relations={} relationships={}",
                document.fileId(), sections.size(), chunks.size(), entityNodes, mentionCount, relationCount,
                relationshipCount);
        return new GraphIndexingResult(nodeCount, relationshipCount, entityNodes, mentionCount, relationCount);
    }

    /**
     * 按 fileId 删除派生结构图；PostgreSQL/MinIO 删除由各自主链路负责。
     */
    public GraphIndexingResult deleteFile(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId 不能为空");
        }
        try (var session = driver.session(availabilityService.sessionConfig())) {
            return session.executeWrite(tx -> {
                Map<String, Object> parameters = Map.of(PARAM_FILE_ID, fileId.trim());
                Map<String, Object> relationParameters = Map.of(
                        PARAM_FILE_ID, fileId.trim(),
                        "chunkIdPrefix", fileId.trim() + ":");
                tx.run(DELETE_OLD_MENTIONS, parameters).consume();
                tx.run(DELETE_FILE_RELATIONS, relationParameters).consume();
                var chunkCounters = tx.run(DELETE_OLD_CHUNKS, parameters).consume().counters();
                var sectionCounters = tx.run(DELETE_OLD_SECTIONS, parameters).consume().counters();
                var documentCounters = tx.run(DELETE_DOCUMENT, parameters).consume().counters();
                int nodesDeleted = chunkCounters.nodesDeleted()
                        + sectionCounters.nodesDeleted()
                        + documentCounters.nodesDeleted();
                int relationshipsDeleted = chunkCounters.relationshipsDeleted()
                        + sectionCounters.relationshipsDeleted()
                        + documentCounters.relationshipsDeleted();
                log.info("Neo4j 文件图删除完成 fileId={} nodesDeleted={} relationshipsDeleted={}",
                        fileId, nodesDeleted, relationshipsDeleted);
                return new GraphIndexingResult(nodesDeleted, relationshipsDeleted);
            });
        }
    }

    private GraphExtractionResult extractKnowledge(GraphSourceDocument document) {
        if (!properties.getIndexing().isEntityExtractionEnabled()) {
            return GraphExtractionResult.empty();
        }
        int maxChunks = properties.getIndexing().getEntityExtractionMaxChunks();
        if (document.chunks().size() > maxChunks) {
            log.warn("实体抽取跳过：分片数 {} 超过阈值 {}，仅写入结构图 fileId={}",
                    document.chunks().size(), maxChunks, document.fileId());
            return GraphExtractionResult.empty();
        }
        if (knowledgeExtractor == null) {
            log.warn("实体抽取已启用但 GraphKnowledgeExtractor 未就绪，跳过实体抽取 fileId={}",
                    document.fileId());
            return GraphExtractionResult.empty();
        }
        try {
            return knowledgeExtractor.extract(document);
        } catch (Exception e) {
            log.warn("实体抽取失败，降级为仅结构图 fileId={} error={}", document.fileId(), e.getMessage(), e);
            return GraphExtractionResult.empty();
        }
    }

    private void writeExtraction(TransactionContext tx, String fileId, GraphExtractionResult extraction) {
        if (extraction.entities().isEmpty() && extraction.relations().isEmpty()) {
            return;
        }
        String version = properties.getIndexing().isEntityExtractionEnabled()
                ? properties.getIndexing().getEntityExtractionVersion()
                : properties.getIndexing().getExtractionVersion();
        final String extractionVersion = (version == null || version.isBlank()) ? "entity-v1" : version;
        List<Map<String, Object>> entityRows = extraction.entities().stream()
                .map(GraphIndexingService::entityRow)
                .toList();
        List<Map<String, Object>> mentionRows = mentionRows(extraction, extractionVersion);
        List<Map<String, Object>> relationRows = extraction.relations().stream()
                .map(relation -> relationRow(relation, extractionVersion))
                .toList();
        runBatches(tx, UPSERT_ENTITIES, fileId, entityRows);
        runBatches(tx, UPSERT_MENTIONS, fileId, mentionRows);
        runBatches(tx, UPSERT_RELATIONS, fileId, relationRows);
    }

    private static int countMentions(GraphExtractionResult extraction) {
        int count = 0;
        for (GraphExtractionEntity entity : extraction.entities()) {
            count += entity.chunkIds().size();
        }
        return count;
    }

    private static List<Map<String, Object>> mentionRows(
            GraphExtractionResult extraction, String extractionVersion) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GraphExtractionEntity entity : extraction.entities()) {
            for (String chunkId : entity.chunkIds()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(PARAM_CHUNK_ID, chunkId);
                row.put("entityKey", entity.entityKey());
                row.put("confidence", entity.confidence());
                row.put("extractionVersion", extractionVersion);
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> entityRow(GraphExtractionEntity entity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entityKey", entity.entityKey());
        row.put("name", entity.name());
        row.put("normalizedName", entity.normalizedName());
        row.put("entityType", entity.entityType().name());
        row.put("aliases", entity.aliases());
        return row;
    }

    private static Map<String, Object> relationRow(
            GraphExtractionRelation relation, String extractionVersion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceEntityKey", relation.sourceEntityKey());
        row.put("targetEntityKey", relation.targetEntityKey());
        row.put("relationType", relation.relationType().name());
        row.put("confidence", relation.confidence());
        row.put("evidence", relation.evidence());
        row.put("evidenceChunkIds", relation.evidenceChunkIds());
        row.put("extractionVersion", extractionVersion);
        return row;
    }

    private void runBatches(
            TransactionContext tx,
            String cypher,
            String fileId,
            List<Map<String, Object>> rows) {
        int batchSize = properties.getIndexing().getBatchSize();
        for (int start = 0; start < rows.size(); start += batchSize) {
            int end = Math.min(rows.size(), start + batchSize);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put(PARAM_FILE_ID, fileId);
            parameters.put("rows", rows.subList(start, end));
            tx.run(cypher, parameters).consume();
        }
    }

    private static Map<String, Object> documentParameters(GraphSourceDocument document) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(PARAM_FILE_ID, document.fileId());
        parameters.put("fileName", document.fileName());
        parameters.put("contentType", document.contentType());
        parameters.put("cleanerVersion", document.cleanerVersion());
        parameters.put("chunkStrategy", document.chunkStrategy());
        return parameters;
    }

    private static List<SectionRow> sections(GraphSourceDocument document) {
        Map<String, SectionRow> unique = new LinkedHashMap<>();
        for (GraphSourceChunk chunk : document.chunks()) {
            String heading = normalizeHeading(chunk.headingPath());
            if (heading == null) {
                continue;
            }
            String id = sectionId(document.fileId(), heading);
            unique.putIfAbsent(id, new SectionRow(id, heading, headingLevel(heading)));
        }
        return List.copyOf(unique.values());
    }

    private static List<ChunkRow> chunks(GraphSourceDocument document) {
        List<ChunkRow> rows = new ArrayList<>(document.chunks().size());
        for (GraphSourceChunk chunk : document.chunks()) {
            String heading = normalizeHeading(chunk.headingPath());
            rows.add(new ChunkRow(
                    chunkId(document.fileId(), chunk.chunkIndex()),
                    chunk.chunkIndex(),
                    chunk.contentHash(),
                    heading,
                    chunk.charCount(),
                    chunk.tokenCount(),
                    preview(chunk.chunkText()),
                    heading == null ? null : sectionId(document.fileId(), heading)));
        }
        return rows;
    }

    private static List<NextRow> nextRows(List<ChunkRow> chunks) {
        if (chunks.size() < 2) {
            return List.of();
        }
        List<NextRow> rows = new ArrayList<>(chunks.size() - 1);
        for (int i = 0; i < chunks.size() - 1; i++) {
            rows.add(new NextRow(chunks.get(i).chunkId(), chunks.get(i + 1).chunkId()));
        }
        return rows;
    }

    static String chunkId(String fileId, int chunkIndex) {
        return fileId + ":" + chunkIndex;
    }

    static String sectionId(String fileId, String headingPath) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(headingPath.getBytes(StandardCharsets.UTF_8));
            return fileId + ":section:" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    static String normalizeHeading(String headingPath) {
        if (headingPath == null || headingPath.isBlank()) {
            return null;
        }
        return collapseWhitespace(headingPath);
    }

    private static String collapseWhitespace(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = !normalized.isEmpty();
            } else {
                if (pendingSpace) {
                    normalized.append(' ');
                    pendingSpace = false;
                }
                normalized.appendCodePoint(codePoint);
            }
        }
        return normalized.toString();
    }

    static int headingLevel(String headingPath) {
        long separators = headingPath.codePoints()
                .filter(codePoint -> codePoint == '>' || codePoint == '/'
                        || codePoint == '»' || codePoint == '›')
                .count();
        return Math.toIntExact(1 + separators);
    }

    static String preview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = collapseWhitespace(content);
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= 300) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, 300);
        return normalized.substring(0, end);
    }

    private static Map<String, Object> toMap(SectionRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sectionId", row.sectionId());
        map.put("headingPath", row.headingPath());
        map.put("level", row.level());
        return map;
    }

    private static Map<String, Object> toMap(ChunkRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(PARAM_CHUNK_ID, row.chunkId());
        map.put("chunkIndex", row.chunkIndex());
        map.put("contentHash", row.contentHash());
        map.put("headingPath", row.headingPath());
        map.put("charCount", row.charCount());
        map.put("tokenCount", row.tokenCount());
        map.put("preview", row.preview());
        return map;
    }

    private static Map<String, Object> toMap(SectionChunkRow row) {
        return Map.of("sectionId", row.sectionId(), PARAM_CHUNK_ID, row.chunkId());
    }

    private static Map<String, Object> toMap(NextRow row) {
        return Map.of("currentId", row.currentId(), "nextId", row.nextId());
    }

    private record SectionRow(String sectionId, String headingPath, int level) {
    }

    private record ChunkRow(
            String chunkId,
            int chunkIndex,
            String contentHash,
            String headingPath,
            int charCount,
            Integer tokenCount,
            String preview,
            String sectionId) {
    }

    private record SectionChunkRow(String sectionId, String chunkId) {
    }

    private record NextRow(String currentId, String nextId) {
    }
}
