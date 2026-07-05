package com.example.myllm.support.retrieval;

import com.example.myllm.dto.VectorChunkResult;

/**
 * 表示 Dense、BM25 与 Graph 多路召回合并后的同一个分片。
 *
 * @param hit 分片正文及元数据
 * @param vectorScore Dense 向量相似度；未被向量通道召回时为 {@code null}
 * @param bm25Score BM25 原始分数；未被 BM25 通道召回时为 {@code null}
 * @param graphScore 图召回分数；未被图通道召回时为 {@code null}
 * @param vectorRank Dense 通道中的一基排名
 * @param bm25Rank BM25 通道中的一基排名
 * @param graphRank Graph 通道中的一基排名
 * @param fusionScore 归一化后的加权 RRF 分数
 * @param matchedEntities 图通道命中的实体名称
 * @param evidencePath 图通道证据路径
 */
public record HybridRetrievalCandidate(
        VectorChunkResult hit,
        Double vectorScore,
        Double bm25Score,
        Double graphScore,
        Integer vectorRank,
        Integer bm25Rank,
        Integer graphRank,
        double fusionScore,
        java.util.List<String> matchedEntities,
        String evidencePath) {

    public HybridRetrievalCandidate {
        matchedEntities = matchedEntities == null ? java.util.List.of() : java.util.List.copyOf(matchedEntities);
    }

    /** 仅 Dense + BM25 两路融合时的便捷构造。 */
    public HybridRetrievalCandidate(
            VectorChunkResult hit,
            Double vectorScore,
            Double bm25Score,
            Integer vectorRank,
            Integer bm25Rank,
            double fusionScore) {
        this(hit, vectorScore, bm25Score, null, vectorRank, bm25Rank, null, fusionScore, java.util.List.of(), null);
    }
}
