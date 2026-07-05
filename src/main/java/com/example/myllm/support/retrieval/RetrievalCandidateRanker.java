package com.example.myllm.support.retrieval;

import com.example.myllm.dto.VectorChunkResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 对向量粗召回结果执行可测试的本地排序编排。
 *
 * <p>处理顺序为：文件名加权 → 内容去重 → 相关性过滤 → 单文档限额 → Final TopK。
 * 文件名加权先于去重，确保相同内容存在于多个文件时优先保留用户明确指定的来源；
 * 相关性过滤先于单文档限额，避免低分块提前占用文件配额。</p>
 */
@Component
public class RetrievalCandidateRanker {

    /**
     * 排序并过滤候选结果。
     *
     * @param query 原始用户问题，用于文件名匹配
     * @param intent 问题意图
     * @param hits 向量粗召回结果
     * @param minSimilarity 普通内容问答的最低向量相似度
     * @param filenameMinSimilarity 明确指定文件时允许的最低向量相似度
     * @param filenameBoost 文件名完全/包含匹配的加权值
     * @param maxChunksPerFile 内容问答每个文件最多保留的块数
     * @param filenameLookupMaxChunks 文件定位意图每个文件最多保留的块数
     * @param finalTopK 最终结果数量
     * @return 排序结果及各阶段计数
     */
    @SuppressWarnings("java:S107") // Ranking thresholds are independent tuning controls.
    public RankingResult rank(
            String query,
            QueryIntent intent,
            List<VectorChunkResult> hits,
            double minSimilarity,
            double filenameMinSimilarity,
            double filenameBoost,
            int maxChunksPerFile,
            int filenameLookupMaxChunks,
            int finalTopK) {
        List<VectorChunkResult> safeHits = hits == null ? List.of() : hits;
        List<ScoredCandidate> boosted = safeHits.stream()
                .map(hit -> score(query, hit, filenameBoost))
                .sorted(Comparator.comparingDouble(ScoredCandidate::adjustedScore).reversed())
                .toList();
        List<ScoredCandidate> deduped = dedupeByContent(boosted);
        List<ScoredCandidate> relevant = deduped.stream()
                .filter(candidate -> isRelevant(candidate, intent, minSimilarity, filenameMinSimilarity))
                .toList();
        int perFileLimit = intent == QueryIntent.FILENAME_LOOKUP
                ? Math.max(1, filenameLookupMaxChunks)
                : Math.max(1, maxChunksPerFile);
        List<ScoredCandidate> limited = limitPerFile(relevant, perFileLimit);
        List<VectorChunkResult> accepted = limited.stream()
                .limit(Math.max(1, finalTopK))
                .map(candidate -> candidate.hit().withSimilarity(candidate.adjustedScore()))
                .toList();
        return new RankingResult(
                accepted,
                safeHits.size(),
                deduped.size(),
                relevant.size(),
                limited.size());
    }

    /**
     * 对 RRF 融合候选执行后处理。
     *
     * <p>BM25 命中和达到向量阈值的 Dense 命中均可进入结果集。这里不会把 RRF 分数与
     * {@code minSimilarity} 比较，因为二者量纲不同。</p>
     *
     * @param query 原始问题，用于文件名加权
     * @param intent 问题意图
     * @param candidates RRF 融合候选
     * @param minSimilarity 普通 Dense 候选最低相似度
     * @param filenameMinSimilarity 明确文件名时的 Dense 最低相似度
     * @param filenameBoost 文件名匹配加权
     * @param maxChunksPerFile 内容问答单文件限额
     * @param filenameLookupMaxChunks 文件定位单文件限额
     * @param finalTopK 最终候选数
     * @return 后处理结果及各阶段计数
     */
    @SuppressWarnings("java:S107") // Ranking thresholds are independent tuning controls.
    public RankingResult rankHybrid(
            String query,
            QueryIntent intent,
            List<HybridRetrievalCandidate> candidates,
            double minSimilarity,
            double filenameMinSimilarity,
            double filenameBoost,
            int maxChunksPerFile,
            int filenameLookupMaxChunks,
            int finalTopK) {
        List<HybridRetrievalCandidate> safeCandidates = candidates == null ? List.of() : candidates;
        List<HybridScoredCandidate> boosted = safeCandidates.stream()
                .map(candidate -> scoreHybrid(query, candidate, filenameBoost))
                .sorted(Comparator.comparingDouble(HybridScoredCandidate::adjustedScore).reversed())
                .toList();
        List<HybridScoredCandidate> deduped = dedupeHybridByContent(boosted);
        List<HybridScoredCandidate> relevant = deduped.stream()
                .filter(candidate -> isHybridRelevant(
                        candidate, intent, minSimilarity, filenameMinSimilarity))
                .toList();
        int perFileLimit = intent == QueryIntent.FILENAME_LOOKUP
                ? Math.max(1, filenameLookupMaxChunks)
                : Math.max(1, maxChunksPerFile);
        List<HybridScoredCandidate> limited = limitHybridPerFile(relevant, perFileLimit);
        List<VectorChunkResult> accepted = limited.stream()
                .limit(Math.max(1, finalTopK))
                .map(candidate -> candidate.candidate().hit().withSimilarity(candidate.adjustedScore()))
                .toList();
        return new RankingResult(
                accepted,
                safeCandidates.size(),
                deduped.size(),
                relevant.size(),
                limited.size());
    }

    private static ScoredCandidate score(String query, VectorChunkResult hit, double boostWeight) {
        double boost = QueryFilenameMatcher.filenameBoost(query, hit.fileName(), boostWeight);
        double adjustedScore = Math.min(1.0, hit.similarity() + boost);
        return new ScoredCandidate(hit, hit.similarity(), adjustedScore, boost > 0.0);
    }

    private static HybridScoredCandidate scoreHybrid(
            String query,
            HybridRetrievalCandidate candidate,
            double boostWeight) {
        double boost = QueryFilenameMatcher.filenameBoost(query, candidate.hit().fileName(), boostWeight);
        double adjustedScore = Math.min(1.0, candidate.fusionScore() + boost);
        return new HybridScoredCandidate(candidate, adjustedScore, boost > 0.0);
    }

    private static boolean isRelevant(
            ScoredCandidate candidate,
            QueryIntent intent,
            double minSimilarity,
            double filenameMinSimilarity) {
        boolean fileScopedIntent = intent == QueryIntent.FILENAME_LOOKUP || intent == QueryIntent.HYBRID;
        double threshold = fileScopedIntent && candidate.filenameMatched()
                ? filenameMinSimilarity
                : minSimilarity;
        return candidate.baseSimilarity() >= threshold;
    }

    private static boolean isHybridRelevant(
            HybridScoredCandidate candidate,
            QueryIntent intent,
            double minSimilarity,
            double filenameMinSimilarity) {
        if (candidate.candidate().bm25Rank() != null || candidate.candidate().graphRank() != null) {
            return true;
        }
        Double vectorScore = candidate.candidate().vectorScore();
        if (vectorScore == null) {
            return false;
        }
        boolean fileScopedIntent = intent == QueryIntent.FILENAME_LOOKUP || intent == QueryIntent.HYBRID;
        double threshold = fileScopedIntent && candidate.filenameMatched()
                ? filenameMinSimilarity
                : minSimilarity;
        return vectorScore >= threshold;
    }

    private static List<ScoredCandidate> dedupeByContent(List<ScoredCandidate> hits) {
        Map<String, ScoredCandidate> bestByKey = new LinkedHashMap<>();
        for (ScoredCandidate candidate : hits) {
            String key = dedupeKey(candidate.hit());
            ScoredCandidate existing = bestByKey.get(key);
            if (existing == null || candidate.adjustedScore() > existing.adjustedScore()) {
                bestByKey.put(key, candidate);
            }
        }
        return bestByKey.values().stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::adjustedScore).reversed())
                .toList();
    }

    private static List<HybridScoredCandidate> dedupeHybridByContent(List<HybridScoredCandidate> hits) {
        Map<String, HybridScoredCandidate> bestByKey = new LinkedHashMap<>();
        for (HybridScoredCandidate candidate : hits) {
            String key = dedupeKey(candidate.candidate().hit());
            HybridScoredCandidate existing = bestByKey.get(key);
            if (existing == null || candidate.adjustedScore() > existing.adjustedScore()) {
                bestByKey.put(key, candidate);
            }
        }
        return bestByKey.values().stream()
                .sorted(Comparator.comparingDouble(HybridScoredCandidate::adjustedScore).reversed())
                .toList();
    }

    private static String dedupeKey(VectorChunkResult hit) {
        if (hit.contentHash() != null && !hit.contentHash().isBlank()) {
            return "hash:" + hit.contentHash();
        }
        return "file:" + hit.fileId() + "#" + hit.chunkIndex();
    }

    private static List<ScoredCandidate> limitPerFile(List<ScoredCandidate> hits, int limit) {
        Map<String, Integer> countByFile = new LinkedHashMap<>();
        List<ScoredCandidate> result = new ArrayList<>();
        for (ScoredCandidate candidate : hits) {
            String fileId = candidate.hit().fileId();
            int count = countByFile.getOrDefault(fileId, 0);
            if (count >= limit) {
                continue;
            }
            countByFile.put(fileId, count + 1);
            result.add(candidate);
        }
        return result;
    }

    private static List<HybridScoredCandidate> limitHybridPerFile(
            List<HybridScoredCandidate> hits,
            int limit) {
        Map<String, Integer> countByFile = new LinkedHashMap<>();
        List<HybridScoredCandidate> result = new ArrayList<>();
        for (HybridScoredCandidate candidate : hits) {
            String fileId = candidate.candidate().hit().fileId();
            int count = countByFile.getOrDefault(fileId, 0);
            if (count >= limit) {
                continue;
            }
            countByFile.put(fileId, count + 1);
            result.add(candidate);
        }
        return result;
    }

    private record ScoredCandidate(
            VectorChunkResult hit,
            double baseSimilarity,
            double adjustedScore,
            boolean filenameMatched) {
    }

    private record HybridScoredCandidate(
            HybridRetrievalCandidate candidate,
            double adjustedScore,
            boolean filenameMatched) {
    }

    public record RankingResult(
            List<VectorChunkResult> acceptedHits,
            int rawHitCount,
            int dedupedHitCount,
            int relevantHitCount,
            int limitedHitCount) {

        public RankingResult {
            acceptedHits = acceptedHits == null ? List.of() : List.copyOf(acceptedHits);
        }
    }
}
