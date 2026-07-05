package com.example.myllm.service;

import com.example.myllm.config.GraphProperties;
import com.example.myllm.dto.VectorChunkResult;
import com.example.myllm.support.graph.EntityNameNormalizer;
import com.example.myllm.support.graph.GraphRetrievalHit;
import com.example.myllm.support.retrieval.GraphQueryTermExtractor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Neo4j 图召回：种子实体匹配 → MENTIONS 反查 Chunk → 可选一跳 RELATED_TO 扩展。
 *
 * <p>查询失败或超时时返回 {@code available=false}，不向上抛出，避免影响 Dense/BM25 主链路。</p>
 */
@Service
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
public class Neo4jGraphRetrievalService implements GraphRetrievalPort {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphRetrievalService.class);

    private static final String EXACT_ENTITY_MATCH = "UNWIND $terms AS term "
            + "MATCH (e:Entity) "
            + "WHERE e.normalizedName = term "
            + "RETURN DISTINCT e.entityKey AS entityKey, e.name AS name, 1.0 AS score";
    private static final String FULLTEXT_ENTITY_MATCH = "CALL db.index.fulltext.queryNodes($indexName, $queryText) "
            + "YIELD node, score "
            + "WHERE node:Entity "
            + "RETURN node.entityKey AS entityKey, node.name AS name, score "
            + "ORDER BY score DESC";
    private static final String CHUNK_BY_ENTITIES = "UNWIND $entityKeys AS entityKey "
            + "MATCH (e:Entity {entityKey: entityKey}) "
            + "OPTIONAL MATCH (e)-[:RELATED_TO]-(related:Entity) "
            + "WITH collect(DISTINCT e) + [r IN collect(DISTINCT related) WHERE r IS NOT NULL] AS entities "
            + "UNWIND entities AS entity "
            + "MATCH (c:Chunk)-[m:MENTIONS]->(entity) "
            + "WHERE size($fileIds) = 0 OR c.fileId IN $fileIds "
            + "WITH c, entity, coalesce(m.confidence, 0.5) AS mentionConfidence "
            + "RETURN c.fileId AS fileId, c.chunkIndex AS chunkIndex, c.headingPath AS headingPath, "
            + "c.contentHash AS contentHash, entity.name AS matchedEntity, mentionConfidence AS graphScore "
            + "ORDER BY graphScore DESC, c.chunkIndex ASC "
            + "LIMIT $candidateTopK";

    private final Driver driver;
    private final Neo4jAvailabilityService availabilityService;
    private final FileEmbeddingService fileEmbeddingService;
    private final GraphProperties properties;
    private final ExecutorService queryExecutor = Executors.newCachedThreadPool();

    public Neo4jGraphRetrievalService(
            Driver driver,
            Neo4jAvailabilityService availabilityService,
            FileEmbeddingService fileEmbeddingService,
            GraphProperties properties) {
        this.driver = driver;
        this.availabilityService = availabilityService;
        this.fileEmbeddingService = fileEmbeddingService;
        this.properties = properties;
    }

    @Override
    public GraphSearchResult search(String query, List<String> fileIds) {
        if (!properties.getRetrieval().isEnabled()) {
            return GraphSearchResult.unavailable("graph.retrieval.disabled");
        }
        if (query == null || query.isBlank()) {
            return GraphSearchResult.unavailable("empty query");
        }
        Neo4jAvailabilityService.Availability availability = availabilityService.check();
        if (!availability.available()) {
            return GraphSearchResult.unavailable(
                    availability.error() == null ? "neo4j unavailable" : availability.error());
        }

        long startNanos = System.nanoTime();
        try {
            Future<GraphSearchResult> future = queryExecutor.submit(
                    () -> searchInternal(query, normalizeFileIds(fileIds)));
            GraphSearchResult result = future.get(
                    properties.getRetrieval().getTimeoutMs(), TimeUnit.MILLISECONDS);
            return withDuration(result, elapsedMillis(startNanos));
        } catch (TimeoutException e) {
            log.warn("Neo4j 图召回超时 queryLen={} timeoutMs={}",
                    query.length(), properties.getRetrieval().getTimeoutMs());
            return withDuration(
                    GraphSearchResult.unavailable("graph timeout"), elapsedMillis(startNanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return withDuration(
                    GraphSearchResult.unavailable("graph interrupted"), elapsedMillis(startNanos));
        } catch (ExecutionException e) {
            String reason = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            log.warn("Neo4j 图召回失败 queryLen={} reason={}", query.length(), reason);
            return withDuration(
                    GraphSearchResult.unavailable(abbreviate(reason, 300)), elapsedMillis(startNanos));
        }
    }

    private GraphSearchResult searchInternal(String query, List<String> fileIds) {
        List<String> terms = GraphQueryTermExtractor.extract(query);
        if (terms.isEmpty()) {
            return GraphSearchResult.unavailable("no query terms");
        }

        int seedTopK = properties.getRetrieval().getSeedTopK();
        List<SeedEntity> seeds = findSeedEntities(terms, seedTopK);
        if (seeds.isEmpty()) {
            return GraphSearchResult.success(List.of(), List.of(), 0, 0);
        }

        List<String> entityKeys = seeds.stream().map(SeedEntity::entityKey).toList();
        List<ChunkCandidate> chunkCandidates = findChunksByEntities(entityKeys, fileIds);
        if (chunkCandidates.isEmpty()) {
            return GraphSearchResult.success(List.of(), List.of(), seeds.size(), 0);
        }

        List<GraphRetrievalHit> rawHits = toRawHits(chunkCandidates);
        List<VectorChunkResult> hydrated = hydrateChunks(rawHits);
        double minScore = properties.getRetrieval().getMinScore();
        List<VectorChunkResult> filtered = hydrated.stream()
                .filter(hit -> hit.similarity() >= minScore)
                .toList();
        return GraphSearchResult.success(filtered, rawHits, seeds.size(), 0);
    }

    private static GraphSearchResult withDuration(GraphSearchResult result, long durationMs) {
        return new GraphSearchResult(
                result.available(),
                result.hits(),
                result.rawHits(),
                result.seedCount(),
                durationMs,
                result.fallbackReason());
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private List<SeedEntity> findSeedEntities(List<String> terms, int seedTopK) {
        Map<String, SeedEntity> seeds = new LinkedHashMap<>();
        List<String> normalizedTerms = terms.stream()
                .map(EntityNameNormalizer::normalize)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();

        try (Session session = driver.session(availabilityService.sessionConfig())) {
            if (!normalizedTerms.isEmpty()) {
                for (Record resultRow : session.run(
                                EXACT_ENTITY_MATCH, Values.parameters("terms", normalizedTerms))
                        .list()) {
                    addSeed(seeds, resultRow, seedTopK);
                }
            }
            if (seeds.size() < seedTopK) {
                String fulltextQuery = GraphQueryTermExtractor.toFulltextQuery(terms);
                if (!fulltextQuery.isBlank()) {
                    for (Record resultRow : session.run(
                                    FULLTEXT_ENTITY_MATCH,
                                    Values.parameters(
                                            "indexName", properties.getRetrieval().getFulltextIndexName(),
                                            "queryText", fulltextQuery))
                            .list()) {
                        if (seeds.size() >= seedTopK) {
                            break;
                        }
                        addSeed(seeds, resultRow, seedTopK);
                    }
                }
            }
        }
        return seeds.values().stream()
                .sorted(Comparator.comparingDouble(SeedEntity::score).reversed())
                .limit(seedTopK)
                .toList();
    }

    private List<ChunkCandidate> findChunksByEntities(List<String> entityKeys, List<String> fileIds) {
        Map<String, ChunkCandidate> merged = new LinkedHashMap<>();
        try (Session session = driver.session(availabilityService.sessionConfig())) {
            for (Record resultRow : session.run(
                            CHUNK_BY_ENTITIES,
                            Values.parameters(
                                    "entityKeys", entityKeys,
                                    "fileIds", fileIds,
                                    "candidateTopK", properties.getRetrieval().getCandidateTopK()))
                    .list()) {
                mergeChunkCandidate(merged, resultRow);
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(ChunkCandidate::graphScore).reversed()
                        .thenComparingInt(ChunkCandidate::chunkIndex))
                .limit(properties.getRetrieval().getCandidateTopK())
                .toList();
    }

    private List<GraphRetrievalHit> toRawHits(List<ChunkCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> new GraphRetrievalHit(
                        candidate.fileId(),
                        candidate.chunkIndex(),
                        candidate.graphScore(),
                        List.copyOf(candidate.matchedEntities()),
                        candidate.evidencePath()))
                .toList();
    }

    private List<VectorChunkResult> hydrateChunks(List<GraphRetrievalHit> rawHits) {
        List<FileEmbeddingService.ChunkKey> keys = rawHits.stream()
                .map(hit -> new FileEmbeddingService.ChunkKey(hit.fileId(), hit.chunkIndex()))
                .toList();
        Map<String, VectorChunkResult> loaded = new LinkedHashMap<>();
        for (VectorChunkResult chunk : fileEmbeddingService.fetchChunksByKeys(keys)) {
            loaded.put(key(chunk.fileId(), chunk.chunkIndex()), chunk);
        }
        List<VectorChunkResult> result = new ArrayList<>();
        for (GraphRetrievalHit rawHit : rawHits) {
            VectorChunkResult chunk = loaded.get(key(rawHit.fileId(), rawHit.chunkIndex()));
            if (chunk == null) {
                continue;
            }
            result.add(chunk.withSimilarity(rawHit.graphScore()));
        }
        return result;
    }

    private static void addSeed(Map<String, SeedEntity> seeds, Record resultRow, int seedTopK) {
        if (seeds.size() >= seedTopK) {
            return;
        }
        String entityKey = resultRow.get("entityKey").asString(null);
        if (entityKey == null || entityKey.isBlank()) {
            return;
        }
        String name = resultRow.get("name").asString(entityKey);
        double score = resultRow.get("score").asDouble(0.0);
        seeds.putIfAbsent(entityKey, new SeedEntity(entityKey, name, score));
    }

    private static void mergeChunkCandidate(Map<String, ChunkCandidate> merged, Record resultRow) {
        ChunkCandidate incoming = fromRecord(resultRow);
        if (incoming == null) {
            return;
        }
        String key = key(incoming.fileId(), incoming.chunkIndex());
        ChunkCandidate existing = merged.get(key);
        merged.put(key, existing == null ? incoming : mergeCandidates(existing, incoming));
    }

    private static ChunkCandidate fromRecord(Record resultRow) {
        String fileId = resultRow.get("fileId").asString(null);
        if (fileId == null || fileId.isBlank()) {
            return null;
        }
        int chunkIndex = resultRow.get("chunkIndex").asInt(-1);
        if (chunkIndex < 0) {
            return null;
        }
        String matchedEntity = resultRow.get("matchedEntity").asString("");
        Set<String> entities = new LinkedHashSet<>();
        if (!matchedEntity.isBlank()) {
            entities.add(matchedEntity);
        }
        return new ChunkCandidate(
                fileId,
                chunkIndex,
                resultRow.get("graphScore").asDouble(0.0),
                entities,
                evidencePath(matchedEntity, chunkIndex, resultRow.get("headingPath").asString(null)));
    }

    private static ChunkCandidate mergeCandidates(ChunkCandidate left, ChunkCandidate right) {
        Set<String> entities = new LinkedHashSet<>(left.matchedEntities());
        entities.addAll(right.matchedEntities());
        if (right.graphScore() >= left.graphScore()) {
            return new ChunkCandidate(
                    right.fileId(),
                    right.chunkIndex(),
                    right.graphScore(),
                    entities,
                    right.evidencePath());
        }
        return new ChunkCandidate(
                left.fileId(),
                left.chunkIndex(),
                left.graphScore(),
                entities,
                left.evidencePath());
    }

    private static String evidencePath(String entityName, int chunkIndex, String headingPath) {
        String entity = entityName == null || entityName.isBlank() ? "Entity" : entityName;
        if (headingPath == null || headingPath.isBlank()) {
            return entity + "->Chunk#" + chunkIndex;
        }
        return entity + "->" + headingPath;
    }

    private static String key(String fileId, int chunkIndex) {
        return fileId + "#" + chunkIndex;
    }

    private static List<String> normalizeFileIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        return fileIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record SeedEntity(String entityKey, String name, double score) {
    }

    private record ChunkCandidate(
            String fileId,
            int chunkIndex,
            double graphScore,
            Set<String> matchedEntities,
            String evidencePath) {
    }
}
