package com.example.myllm.eval.support;

import com.example.myllm.eval.dto.EvalApiModels.EvalDimensionScores;
import com.example.myllm.eval.entity.ModelEvalItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** 评测分数解析与汇总。 */
public final class EvalScoreSupport {

    private EvalScoreSupport() {
    }

    public static EvalJudgeResult parseJudgeJson(ObjectMapper objectMapper, String raw) {
        JsonNode root = JsonPayloadRepairer.parseLenient(objectMapper, raw);
        return new EvalJudgeResult(
                clampScore(root.path("understanding").asInt(-1)),
                clampScore(root.path("reasoning").asInt(-1)),
                clampScore(root.path("code").asInt(-1)),
                clampScore(root.path("domain").asInt(-1)),
                clampScore(root.path("stability").asInt(-1)),
                clampScore(root.path("instruction").asInt(-1)),
                textOrNull(root.path("summary")),
                raw);
    }

    public static double averageItemScore(ModelEvalItem item) {
        List<Integer> scores = List.of(
                item.getUnderstanding(),
                item.getReasoning(),
                item.getCode(),
                item.getDomain(),
                item.getStability(),
                item.getInstruction());
        long count = scores.stream().filter(s -> s != null && s >= 0).count();
        if (count == 0) {
            return 0.0;
        }
        double sum = scores.stream().filter(s -> s != null && s >= 0).mapToInt(Integer::intValue).sum();
        return round1(sum / count);
    }

    public static EvalDimensionScores aggregateDimensions(List<ModelEvalItem> items) {
        return new EvalDimensionScores(
                avgDim(items, ModelEvalItem::getUnderstanding),
                avgDim(items, ModelEvalItem::getReasoning),
                avgDim(items, ModelEvalItem::getCode),
                avgDim(items, ModelEvalItem::getDomain),
                avgDim(items, ModelEvalItem::getStability),
                avgDim(items, ModelEvalItem::getInstruction));
    }

    public static double aggregateTotalScore(List<ModelEvalItem> items) {
        List<ModelEvalItem> scored = items.stream()
                .filter(i -> i.getItemScore() != null)
                .toList();
        if (scored.isEmpty()) {
            return 0.0;
        }
        double sum = scored.stream().mapToDouble(ModelEvalItem::getItemScore).sum();
        return round1(sum / scored.size());
    }

    public static void applyScores(ModelEvalItem item, EvalJudgeResult result) {
        item.setUnderstanding(result.understanding());
        item.setReasoning(result.reasoning());
        item.setCode(result.code());
        item.setDomain(result.domain());
        item.setStability(result.stability());
        item.setInstruction(result.instruction());
        item.setJudgeSummary(result.summary());
        item.setJudgeRawJson(result.rawJson());
        item.setItemScore(averageItemScore(item));
    }

    public static void applyManualScores(
            ModelEvalItem item,
            Integer understanding,
            Integer reasoning,
            Integer code,
            Integer domain,
            Integer stability,
            Integer instruction,
            String judgeSummary) {
        if (understanding != null) {
            item.setUnderstanding(clampScore(understanding));
        }
        if (reasoning != null) {
            item.setReasoning(clampScore(reasoning));
        }
        if (code != null) {
            item.setCode(clampScore(code));
        }
        if (domain != null) {
            item.setDomain(clampScore(domain));
        }
        if (stability != null) {
            item.setStability(clampScore(stability));
        }
        if (instruction != null) {
            item.setInstruction(clampScore(instruction));
        }
        if (judgeSummary != null) {
            item.setJudgeSummary(judgeSummary);
        }
        item.setManuallyEdited(true);
        item.setItemScore(averageItemScore(item));
    }

    private static Double avgDim(List<ModelEvalItem> items, java.util.function.Function<ModelEvalItem, Integer> getter) {
        List<Integer> values = items.stream()
                .map(getter)
                .filter(v -> v != null && v >= 0)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        double sum = values.stream().mapToInt(Integer::intValue).sum();
        return round1(sum / values.size());
    }

    private static int clampScore(int score) {
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 5);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    public record EvalJudgeResult(
            int understanding,
            int reasoning,
            int code,
            int domain,
            int stability,
            int instruction,
            String summary,
            String rawJson) {}
}
