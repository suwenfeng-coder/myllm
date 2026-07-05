package com.example.myllm.support.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.dto.VectorChunkResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagRetrievalSupportTests {

    private final RetrievalCandidateRanker ranker = new RetrievalCandidateRanker();

    @Test
    void boostedScorePrefersMatchingFilename() {
        VectorChunkResult manual2023 = chunk("f3", "《汽车用户手册（2023年版）》.docx", 0.493);
        VectorChunkResult manual2024 = chunk("f1", "《汽车用户手册（2024年版）》.docx", 0.544);

        double boost2023 = QueryFilenameMatcher.filenameBoost(
                "查看《汽车用户手册（2023年版）》.docx", manual2023.fileName(), 0.12);
        double boost2024 = QueryFilenameMatcher.filenameBoost(
                "查看《汽车用户手册（2023年版）》.docx", manual2024.fileName(), 0.12);

        assertEquals(0.613, Math.min(1.0, manual2023.similarity() + boost2023), 0.0001);
        assertEquals(0.544, Math.min(1.0, manual2024.similarity() + boost2024), 0.0001);
    }

    @Test
    void filenameBoostRunsBeforeDeduplicationAndKeepsRequestedSource() {
        VectorChunkResult target = chunk(
                "target", "《汽车用户手册（2023年版）》.docx", 0.42, "same-content");
        VectorChunkResult other = chunk(
                "other", "贷记卡说明书.docx", 0.50, "same-content");

        var result = ranker.rank(
                "查看《汽车用户手册（2023年版）》.docx",
                QueryIntent.FILENAME_LOOKUP,
                List.of(other, target),
                0.45,
                0.20,
                0.12,
                2,
                1,
                5);

        assertEquals(1, result.acceptedHits().size());
        assertEquals("target", result.acceptedHits().get(0).fileId());
        assertEquals(0.54, result.acceptedHits().get(0).similarity(), 0.0001);
    }

    @Test
    void contentQuestionAllowsMultipleChunksFromSameFile() {
        var result = ranker.rank(
                "胎压报警怎么处理",
                QueryIntent.CONTENT_QA,
                List.of(
                        chunk("manual", "汽车手册.docx", 0.82, "h1"),
                        chunk("manual", "汽车手册.docx", 0.78, "h2"),
                        chunk("manual", "汽车手册.docx", 0.74, "h3")),
                0.45,
                0.20,
                0.12,
                2,
                1,
                5);

        assertEquals(2, result.acceptedHits().size());
        assertTrue(result.acceptedHits().stream().allMatch(hit -> hit.fileId().equals("manual")));
    }

    @Test
    void filenameMatchCannotRescueCompletelyUnrelatedChunk() {
        var result = ranker.rank(
                "查看《汽车用户手册（2023年版）》.docx",
                QueryIntent.FILENAME_LOOKUP,
                List.of(chunk("target", "《汽车用户手册（2023年版）》.docx", 0.10, "h1")),
                0.45,
                0.20,
                0.12,
                2,
                1,
                5);

        assertTrue(result.acceptedHits().isEmpty());
    }

    @Test
    void bm25OnlyCandidateIsNotFilteredByVectorThreshold() {
        VectorChunkResult target = chunk(
                "target", "汽车用户手册（2025年版）.docx", 6.8, "bm25-target");
        HybridRetrievalCandidate candidate = new HybridRetrievalCandidate(
                target, null, 6.8, null, 1, 0.5);

        var result = ranker.rankHybrid(
                "查看汽车用户手册（2025年版）",
                QueryIntent.FILENAME_LOOKUP,
                List.of(candidate),
                0.45,
                0.20,
                0.12,
                2,
                1,
                5);

        assertEquals(1, result.acceptedHits().size());
        assertEquals("target", result.acceptedHits().get(0).fileId());
    }

    @Test
    void denseOnlyHybridCandidateStillUsesVectorThreshold() {
        VectorChunkResult noise = chunk("noise", "贷记卡说明书.docx", 0.30, "dense-noise");
        HybridRetrievalCandidate candidate = new HybridRetrievalCandidate(
                noise, 0.30, null, 1, null, 0.5);

        var result = ranker.rankHybrid(
                "汽车胎压报警怎么处理",
                QueryIntent.CONTENT_QA,
                List.of(candidate),
                0.45,
                0.20,
                0.12,
                2,
                1,
                5);

        assertTrue(result.acceptedHits().isEmpty());
    }

    private static VectorChunkResult chunk(String fileId, String fileName, double similarity) {
        return chunk(fileId, fileName, similarity, fileName);
    }

    private static VectorChunkResult chunk(
            String fileId, String fileName, double similarity, String contentHash) {
        return new VectorChunkResult(
                fileId, fileName, 0, "preview", similarity, null, 100, 50, contentHash, Map.of());
    }
}
