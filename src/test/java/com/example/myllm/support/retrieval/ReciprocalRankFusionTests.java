package com.example.myllm.support.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.dto.VectorChunkResult;
import com.example.myllm.support.graph.GraphRetrievalHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTests {

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    @Test
    void candidatePresentInBothChannelsRanksBeforeSingleChannelCandidate() {
        VectorChunkResult noise = chunk("credit", 0, "贷记卡说明书.docx", 0.56, "noise");
        VectorChunkResult targetDense = chunk("manual", 0, "汽车用户手册（2025年版）.docx", 0.53, "target");
        VectorChunkResult targetBm25 = targetDense.withSimilarity(8.4);

        List<HybridRetrievalCandidate> result = fusion.fuse(
                List.of(noise, targetDense),
                List.of(targetBm25),
                60);

        assertEquals(2, result.size());
        assertEquals("manual", result.get(0).hit().fileId());
        assertNotNull(result.get(0).vectorRank());
        assertNotNull(result.get(0).bm25Rank());
        assertTrue(result.get(0).fusionScore() > result.get(1).fusionScore());
    }

    @Test
    void sameChunkIsMergedByFileAndChunkIndex() {
        VectorChunkResult dense = chunk("manual", 3, "汽车手册.docx", 0.70, "hash");
        VectorChunkResult sparse = dense.withSimilarity(5.2);

        List<HybridRetrievalCandidate> result = fusion.fuse(List.of(dense), List.of(sparse), 60);

        assertEquals(1, result.size());
        assertEquals(0.70, result.get(0).vectorScore(), 0.0001);
        assertEquals(5.2, result.get(0).bm25Score(), 0.0001);
        assertEquals(1.0, result.get(0).fusionScore(), 0.0001);
    }

    @Test
    void threeChannelFusionPrefersChunkPresentInAllChannels() {
        VectorChunkResult graphOnly = chunk("graph", 1, "图文档.docx", 0.88, "g1");
        VectorChunkResult shared = chunk("shared", 2, "共享文档.docx", 0.91, "shared");
        VectorChunkResult denseNoise = chunk("noise", 0, "噪声.docx", 0.95, "noise");

        List<HybridRetrievalCandidate> result = fusion.fuseWeighted(
                List.of(denseNoise, shared),
                List.of(shared.withSimilarity(7.1)),
                List.of(shared.withSimilarity(0.93), graphOnly),
                List.of(new GraphRetrievalHit("shared", 2, 0.93, List.of("呆账核销"), "呆账核销->基本定义")),
                60,
                1.0,
                0.9,
                0.8);

        assertEquals("shared", result.get(0).hit().fileId());
        assertNotNull(result.get(0).vectorRank());
        assertNotNull(result.get(0).bm25Rank());
        assertNotNull(result.get(0).graphRank());
        assertEquals(List.of("呆账核销"), result.get(0).matchedEntities());
    }

    private static VectorChunkResult chunk(
            String fileId,
            int chunkIndex,
            String fileName,
            double score,
            String contentHash) {
        return new VectorChunkResult(
                fileId, fileName, chunkIndex, "preview", score, null, 100, 50, contentHash, Map.of());
    }
}
