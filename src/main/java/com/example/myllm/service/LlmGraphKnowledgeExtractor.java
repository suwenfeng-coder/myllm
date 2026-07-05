package com.example.myllm.service;

import com.example.myllm.config.GraphProperties;
import com.example.myllm.support.graph.EntityNameNormalizer;
import com.example.myllm.support.graph.GraphEntityType;
import com.example.myllm.support.graph.GraphExtractionEntity;
import com.example.myllm.support.graph.GraphExtractionRelation;
import com.example.myllm.support.graph.GraphExtractionResult;
import com.example.myllm.support.graph.GraphRelationType;
import com.example.myllm.support.graph.GraphSourceChunk;
import com.example.myllm.support.graph.GraphSourceDocument;
import com.example.myllm.support.graph.JsonPayloadRepairer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * 使用本地 LLM 从 Chunk 批量抽取实体和关系。
 *
 * <p>输出固定 JSON 协议，低于置信度阈值或类型不在白名单的条目会被丢弃。</p>
 */
@Service
@ConditionalOnExpression("'${graph.enabled:false}' == 'true' && '${graph.indexing.entity-extraction-enabled:false}' == 'true'")
public class LlmGraphKnowledgeExtractor implements GraphKnowledgeExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmGraphKnowledgeExtractor.class);
    private static final Pattern JSON_BLOCK = Pattern.compile(
            "```(?:json)?+[\\t ]*+\\R?+(.*?)```",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CASE);

    private final ChatClient chatClient;
    private final GraphProperties properties;
    private final ObjectMapper objectMapper;

    public LlmGraphKnowledgeExtractor(
            ChatClient chatClient,
            GraphProperties properties,
            ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public GraphExtractionResult extract(GraphSourceDocument document) {
        if (document.chunks().isEmpty()) {
            return GraphExtractionResult.empty();
        }
        int batchSize = properties.getIndexing().getEntityExtractionBatchSize();
        Map<String, MutableEntity> entities = new LinkedHashMap<>();
        List<GraphExtractionRelation> relations = new ArrayList<>();
        List<GraphSourceChunk> chunks = document.chunks();

        int totalBatches = (chunks.size() + batchSize - 1) / batchSize;
        int failedBatches = 0;
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(chunks.size(), start + batchSize);
            List<GraphSourceChunk> batch = chunks.subList(start, end);
            try {
                String response = callModel(document.fileName(), batch);
                BatchParseResult parsed = parseBatch(response, batch, document.fileId());
                mergeEntities(entities, parsed.entities());
                relations.addAll(parsed.relations());
            } catch (Exception e) {
                failedBatches++;
                log.warn("实体抽取批次失败，跳过 fileId={} batchStart={} batchSize={} error={}",
                        document.fileId(), start, batch.size(), e.getMessage());
            }
        }

        List<GraphExtractionEntity> entityRows = entities.values().stream()
                .map(MutableEntity::toRecord)
                .toList();
        if (failedBatches > 0) {
            log.warn("实体抽取部分批次失败 fileId={} failedBatches={} totalBatches={} entities={} relations={}",
                    document.fileId(), failedBatches, totalBatches, entityRows.size(), relations.size());
        }
        log.info("实体抽取完成 fileId={} entities={} relations={} chunks={}",
                document.fileId(), entityRows.size(), relations.size(), chunks.size());
        return new GraphExtractionResult(entityRows, relations);
    }

    private String callModel(String fileName, List<GraphSourceChunk> batch) {
        String prompt = buildPrompt(fileName, batch);
        return chatClient.prompt()
                .system(systemPrompt())
                .user(prompt)
                .call()
                .content();
    }

    @SuppressWarnings("java:S3400") // Method keeps the large prompt out of the call chain.
    private static String systemPrompt() {
        return """
                你是金融制度文档知识图谱抽取器。只输出 JSON，不要解释。
                实体类型白名单: ORGANIZATION, PRODUCT, CARD, ACCOUNT, POLICY, PROCESS, ROLE, TERM, TABLE, FIELD, SYSTEM
                关系类型白名单: BELONGS_TO, APPLIES_TO, REQUIRES, PROHIBITS, DEPENDS_ON, USES, CONTAINS, MAPS_TO, PRECEDES
                输出格式:
                {
                  "entities": [
                    {"localId":"e1","name":"实体名","type":"TERM","aliases":[],"confidence":0.9,"chunkIndex":0}
                  ],
                  "relations": [
                    {"sourceId":"e1","targetId":"e2","type":"APPLIES_TO","confidence":0.85,"evidence":"原文短句","chunkIndex":0}
                  ]
                }
                规则:
                1. 只抽取文本中明确出现的实体，不要臆造。
                2. confidence 取值 0~1；低于阈值的不要输出。
                3. relations 的 sourceId/targetId 必须引用本次 entities 的 localId。
                4. chunkIndex 必须是输入片段的序号。
                """;
    }

    private static String buildPrompt(String fileName, List<GraphSourceChunk> batch) {
        StringBuilder builder = new StringBuilder();
        builder.append("文件名: ").append(fileName).append('\n');
        builder.append("文档片段:\n");
        for (int i = 0; i < batch.size(); i++) {
            GraphSourceChunk chunk = batch.get(i);
            builder.append("[片段").append(i).append(" chunkIndex=").append(chunk.chunkIndex()).append("]\n");
            if (chunk.headingPath() != null && !chunk.headingPath().isBlank()) {
                builder.append("章节: ").append(chunk.headingPath()).append('\n');
            }
            builder.append(chunk.chunkText()).append("\n\n");
        }
        return builder.toString();
    }

    @SuppressWarnings({"java:S135", "java:S3776", "java:S6541"})
    private BatchParseResult parseBatch(
            String response,
            List<GraphSourceChunk> batch,
            String fileId) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("LLM 实体抽取返回空响应");
        }
        JsonNode root = readJson(response);
        double minConfidence = properties.getIndexing().getMinConfidence();
        Map<String, String> localToEntityKey = new LinkedHashMap<>();
        List<GraphExtractionEntity> entities = new ArrayList<>();

        JsonNode entityNodes = root.path("entities");
        if (entityNodes.isArray()) {
            for (JsonNode node : entityNodes) {
                String localId = text(node, "localId");
                String name = EntityNameNormalizer.normalize(text(node, "name"));
                if (localId == null || localId.isBlank() || name.isBlank()) {
                    continue;
                }
                double confidence = node.path("confidence").asDouble(0.0);
                if (confidence < minConfidence) {
                    continue;
                }
                GraphEntityType type = GraphEntityType.parse(text(node, "type")).orElse(null);
                if (type == null) {
                    continue;
                }
                int chunkIndex = resolveChunkIndex(node, batch);
                if (chunkIndex < 0) {
                    continue;
                }
                String entityKey = EntityNameNormalizer.entityKey(type, name);
                localToEntityKey.put(localId, entityKey);
                List<String> aliases = readAliases(node.path("aliases"));
                String chunkId = GraphIndexingService.chunkId(fileId, chunkIndex);
                entities.add(new GraphExtractionEntity(
                        entityKey, name, name, type, aliases, confidence, List.of(chunkId)));
            }
        }

        List<GraphExtractionRelation> relations = new ArrayList<>();
        JsonNode relationNodes = root.path("relations");
        if (relationNodes.isArray()) {
            for (JsonNode node : relationNodes) {
                String sourceId = text(node, "sourceId");
                String targetId = text(node, "targetId");
                String sourceKey = localToEntityKey.get(sourceId);
                String targetKey = localToEntityKey.get(targetId);
                if (sourceKey == null || targetKey == null) {
                    continue;
                }
                double confidence = node.path("confidence").asDouble(0.0);
                if (confidence < minConfidence) {
                    continue;
                }
                GraphRelationType relationType = GraphRelationType.parse(text(node, "type")).orElse(null);
                if (relationType == null) {
                    continue;
                }
                int chunkIndex = resolveChunkIndex(node, batch);
                String evidence = EntityNameNormalizer.normalize(text(node, "evidence"));
                List<String> evidenceChunkIds = chunkIndex >= 0
                        ? List.of(GraphIndexingService.chunkId(fileId, chunkIndex))
                        : List.of();
                relations.add(new GraphExtractionRelation(
                        sourceKey, targetKey, relationType, confidence, evidence, evidenceChunkIds));
            }
        }
        return new BatchParseResult(entities, relations);
    }

    private JsonNode readJson(String response) {
        String trimmed = response.trim();
        Matcher matcher = JSON_BLOCK.matcher(trimmed);
        if (matcher.find()) {
            trimmed = matcher.group(1).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            trimmed = trimmed.substring(start, end + 1);
        }
        return JsonPayloadRepairer.parseLenient(objectMapper, trimmed);
    }

    private static int resolveChunkIndex(JsonNode node, List<GraphSourceChunk> batch) {
        if (!node.has("chunkIndex")) {
            return batch.isEmpty() ? -1 : batch.get(0).chunkIndex();
        }
        int requested = node.path("chunkIndex").asInt(-1);
        for (GraphSourceChunk chunk : batch) {
            if (chunk.chunkIndex() == requested) {
                return requested;
            }
        }
        if (requested >= 0 && requested < batch.size()) {
            return batch.get(requested).chunkIndex();
        }
        return -1;
    }

    private static List<String> readAliases(JsonNode aliasesNode) {
        if (!aliasesNode.isArray()) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        for (JsonNode alias : aliasesNode) {
            String normalized = EntityNameNormalizer.normalize(alias.asText(""));
            if (!normalized.isBlank()) {
                aliases.add(normalized);
            }
        }
        return aliases;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void mergeEntities(Map<String, MutableEntity> target, List<GraphExtractionEntity> incoming) {
        for (GraphExtractionEntity entity : incoming) {
            target.compute(entity.entityKey(), (key, existing) -> {
                if (existing == null) {
                    return MutableEntity.from(entity);
                }
                existing.merge(entity);
                return existing;
            });
        }
    }

    private record BatchParseResult(
            List<GraphExtractionEntity> entities, List<GraphExtractionRelation> relations) {
    }

    private static final class MutableEntity {
        private final String entityKey;
        private String name;
        private final GraphEntityType entityType;
        private final Set<String> aliases = new LinkedHashSet<>();
        private double confidence;
        private final Set<String> chunkIds = new LinkedHashSet<>();

        private MutableEntity(
                String entityKey,
                String name,
                GraphEntityType entityType,
                double confidence) {
            this.entityKey = entityKey;
            this.name = name;
            this.entityType = entityType;
            this.confidence = confidence;
        }

        static MutableEntity from(GraphExtractionEntity entity) {
            MutableEntity mutable = new MutableEntity(
                    entity.entityKey(), entity.name(), entity.entityType(), entity.confidence());
            mutable.aliases.addAll(entity.aliases());
            mutable.chunkIds.addAll(entity.chunkIds());
            return mutable;
        }

        void merge(GraphExtractionEntity entity) {
            if (entity.name().length() > name.length()) {
                name = entity.name();
            }
            confidence = Math.max(confidence, entity.confidence());
            aliases.addAll(entity.aliases());
            chunkIds.addAll(entity.chunkIds());
        }

        GraphExtractionEntity toRecord() {
            return new GraphExtractionEntity(
                    entityKey,
                    name,
                    EntityNameNormalizer.normalize(name),
                    entityType,
                    List.copyOf(aliases),
                    confidence,
                    List.copyOf(chunkIds));
        }
    }
}
