package com.example.myllm.support.retrieval;

import com.example.myllm.dto.VectorChunkResult;
import com.example.myllm.support.graph.GraphRetrievalHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 使用加权 Reciprocal Rank Fusion（RRF）融合 Dense、BM25 与 Graph 多路排名。
 *
 * <p>RRF 只依赖各召回通道的排名，不直接混合量纲不同的原始分数。
 * 输出分数按参与通道的理论最大值归一化到 {@code [0, 1]}，便于日志和页面展示。</p>
 */
@Component
public class ReciprocalRankFusion {

    public static final double DENSE_WEIGHT = 1.00;
    public static final double BM25_WEIGHT = 0.90;

    /**
     * 合并 Dense 与 BM25 两路候选（兼容旧调用）。
     */
    public List<HybridRetrievalCandidate> fuse(
            List<VectorChunkResult> denseHits,
            List<VectorChunkResult> bm25Hits,
            int rrfK) {
        return fuseWeighted(
                denseHits,
                bm25Hits,
                List.of(),
                List.of(),
                rrfK,
                DENSE_WEIGHT,
                BM25_WEIGHT,
                0.0);
    }

    /**
     * 合并 Dense、BM25 与 Graph 三路候选。
     *
     * @param graphWeight Graph 通道 RRF 权重，为 0 时忽略图结果
     */
    @SuppressWarnings({"java:S107", "java:S3776", "java:S6541"})
    public List<HybridRetrievalCandidate> fuseWeighted(
            List<VectorChunkResult> denseHits,
            List<VectorChunkResult> bm25Hits,
            List<VectorChunkResult> graphHits,
            List<GraphRetrievalHit> graphEvidence,
            int rrfK,
            double denseWeight,
            double bm25Weight,
            double graphWeight) {
        int safeK = Math.max(1, rrfK);
        Map<String, MutableCandidate> merged = new LinkedHashMap<>();
        Map<String, GraphRetrievalHit> evidenceByKey = indexGraphEvidence(graphEvidence);

        List<VectorChunkResult> safeDense = denseHits == null ? List.of() : denseHits;
        for (int i = 0; i < safeDense.size(); i++) {
            VectorChunkResult hit = safeDense.get(i);
            MutableCandidate candidate = merged.computeIfAbsent(key(hit), ignored -> new MutableCandidate(hit));
            candidate.hit = hit;
            candidate.vectorScore = hit.similarity();
            candidate.vectorRank = i + 1;
        }

        List<VectorChunkResult> safeBm25 = bm25Hits == null ? List.of() : bm25Hits;
        for (int i = 0; i < safeBm25.size(); i++) {
            VectorChunkResult hit = safeBm25.get(i);
            MutableCandidate candidate = merged.computeIfAbsent(key(hit), ignored -> new MutableCandidate(hit));
            candidate.hit = hit;
            candidate.bm25Score = hit.similarity();
            candidate.bm25Rank = i + 1;
        }

        List<VectorChunkResult> safeGraph = graphHits == null ? List.of() : graphHits;
        if (graphWeight > 0.0) {
            for (int i = 0; i < safeGraph.size(); i++) {
                VectorChunkResult hit = safeGraph.get(i);
                MutableCandidate candidate = merged.computeIfAbsent(key(hit), ignored -> new MutableCandidate(hit));
                candidate.hit = hit;
                candidate.graphScore = hit.similarity();
                candidate.graphRank = i + 1;
                GraphRetrievalHit evidence = evidenceByKey.get(key(hit));
                if (evidence != null) {
                    candidate.matchedEntities = evidence.matchedEntities();
                    candidate.evidencePath = evidence.evidencePath();
                }
            }
        }

        double maximumScore = 0.0;
        if (!safeDense.isEmpty()) {
            maximumScore += denseWeight / (safeK + 1.0);
        }
        if (!safeBm25.isEmpty()) {
            maximumScore += bm25Weight / (safeK + 1.0);
        }
        if (graphWeight > 0.0 && !safeGraph.isEmpty()) {
            maximumScore += graphWeight / (safeK + 1.0);
        }
        if (maximumScore <= 0.0) {
            return List.of();
        }

        List<HybridRetrievalCandidate> result = new ArrayList<>(merged.size());
        for (MutableCandidate candidate : merged.values()) {
            double rawScore = weightedReciprocal(candidate.vectorRank, safeK, denseWeight)
                    + weightedReciprocal(candidate.bm25Rank, safeK, bm25Weight)
                    + weightedReciprocal(candidate.graphRank, safeK, graphWeight);
            result.add(new HybridRetrievalCandidate(
                    candidate.hit,
                    candidate.vectorScore,
                    candidate.bm25Score,
                    candidate.graphScore,
                    candidate.vectorRank,
                    candidate.bm25Rank,
                    candidate.graphRank,
                    rawScore / maximumScore,
                    candidate.matchedEntities,
                    candidate.evidencePath));
        }
        return result.stream()
                .sorted(Comparator.comparingDouble(HybridRetrievalCandidate::fusionScore).reversed())
                .toList();
    }

    private static Map<String, GraphRetrievalHit> indexGraphEvidence(List<GraphRetrievalHit> graphEvidence) {
        Map<String, GraphRetrievalHit> indexed = new LinkedHashMap<>();
        if (graphEvidence == null) {
            return indexed;
        }
        for (GraphRetrievalHit hit : graphEvidence) {
            indexed.put(hit.fileId() + "#" + hit.chunkIndex(), hit);
        }
        return indexed;
    }

    private static double weightedReciprocal(Integer rank, int rrfK, double weight) {
        if (rank == null || weight <= 0.0) {
            return 0.0;
        }
        return weight / (rrfK + rank);
    }

    private static String key(VectorChunkResult hit) {
        return hit.fileId() + "#" + hit.chunkIndex();
    }

    private static final class MutableCandidate {
        private VectorChunkResult hit;
        private Double vectorScore;
        private Double bm25Score;
        private Double graphScore;
        private Integer vectorRank;
        private Integer bm25Rank;
        private Integer graphRank;
        private List<String> matchedEntities = List.of();
        private String evidencePath;

        private MutableCandidate(VectorChunkResult hit) {
            this.hit = hit;
        }
    }
}
