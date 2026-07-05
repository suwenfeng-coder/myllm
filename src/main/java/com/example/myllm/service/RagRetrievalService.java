package com.example.myllm.service;

import com.example.myllm.config.GraphProperties;
import com.example.myllm.dto.VectorChunkResult;
import com.example.myllm.support.retrieval.HybridRetrievalCandidate;
import com.example.myllm.support.retrieval.QueryIntent;
import com.example.myllm.support.retrieval.QueryRewriteResult;
import com.example.myllm.support.retrieval.QueryRewriteService;
import com.example.myllm.support.retrieval.ReciprocalRankFusion;
import com.example.myllm.support.retrieval.RetrievalCandidateRanker;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * RAG 检索编排：扩大候选召回、Neo4j 图召回、多路 RRF 融合、去重与最终截断。
 */
@Service
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    private final FileEmbeddingService fileEmbeddingService;
    private final Bm25RetrievalService bm25RetrievalService;
    private final GraphRetrievalPort graphRetrievalPort;
    private final GraphProperties graphProperties;
    private final QueryRewriteService queryRewriteService;
    private final ReciprocalRankFusion reciprocalRankFusion;
    private final RetrievalCandidateRanker candidateRanker;
    private final boolean hybridEnabled;
    private final int candidateTopK;
    private final int bm25TopK;
    private final int rrfK;
    private final int finalTopK;
    private final double minSimilarity;
    private final double filenameBoost;
    private final int maxChunksPerFile;
    private final int filenameLookupMaxChunks;
    private final double filenameMinSimilarity;

    public RagRetrievalService(
            FileEmbeddingService fileEmbeddingService,
            Bm25RetrievalService bm25RetrievalService,
            GraphRetrievalPort graphRetrievalPort,
            GraphProperties graphProperties,
            QueryRewriteService queryRewriteService,
            ReciprocalRankFusion reciprocalRankFusion,
            RetrievalCandidateRanker candidateRanker,
            @Value("${rag.retrieval.hybrid-enabled:true}") boolean hybridEnabled,
            @Value("${rag.retrieval.candidate-top-k:30}") int candidateTopK,
            @Value("${rag.retrieval.bm25-top-k:30}") int bm25TopK,
            @Value("${rag.retrieval.rrf-k:60}") int rrfK,
            @Value("${rag.retrieval.final-top-k:5}") int finalTopK,
            @Value("${rag.retrieval.min-similarity:0.45}") double minSimilarity,
            @Value("${rag.retrieval.filename-boost:0.12}") double filenameBoost,
            @Value("${rag.retrieval.max-chunks-per-file:2}") int maxChunksPerFile,
            @Value("${rag.retrieval.filename-lookup-max-chunks:1}") int filenameLookupMaxChunks,
            @Value("${rag.retrieval.filename-min-similarity:0.20}") double filenameMinSimilarity) {
        this.fileEmbeddingService = fileEmbeddingService;
        this.bm25RetrievalService = bm25RetrievalService;
        this.graphRetrievalPort = graphRetrievalPort;
        this.graphProperties = graphProperties;
        this.queryRewriteService = queryRewriteService;
        this.reciprocalRankFusion = reciprocalRankFusion;
        this.candidateRanker = candidateRanker;
        this.hybridEnabled = hybridEnabled;
        this.finalTopK = Math.max(1, finalTopK);
        this.candidateTopK = Math.max(this.finalTopK, candidateTopK);
        this.bm25TopK = Math.max(this.finalTopK, bm25TopK);
        this.rrfK = Math.max(1, rrfK);
        this.minSimilarity = clampSimilarity(minSimilarity);
        this.filenameMinSimilarity = clampSimilarity(filenameMinSimilarity);
        this.filenameBoost = Math.max(0.0, Math.min(1.0, filenameBoost));
        this.maxChunksPerFile = Math.max(1, maxChunksPerFile);
        this.filenameLookupMaxChunks = Math.max(1, filenameLookupMaxChunks);
    }

    @SuppressWarnings({"java:S3776", "java:S6541"}) // Retrieval orchestration keeps fallback state explicit.
    public RetrievalResult retrieve(String query, List<String> fileIds) {
        QueryRewriteResult rewrite = queryRewriteService.rewrite(query);
        List<String> requestedFileIds = normalizeFileIds(fileIds);
        if (rewrite.skipRag()) {
            if (log.isInfoEnabled()) {
                log.info("RAG 已跳过 intent={} originalQuery={}", rewrite.intent(), rewrite.originalQuery());
            }
            return new RetrievalResult(
                    List.of(),
                    List.of(),
                    rewrite,
                    requestedFileIds,
                    RetrievalDiagnostics.skipped());
        }

        List<String> effectiveFileIds = resolveEffectiveFileIds(rewrite, requestedFileIds);
        List<VectorChunkResult> denseHits =
                fileEmbeddingService.search(rewrite.retrievalQuery(), candidateTopK, effectiveFileIds).results();

        GraphRetrievalPort.GraphSearchResult graphResult = graphRetrievalPort.search(
                rewrite.retrievalQuery(), effectiveFileIds);
        boolean graphAttempted = graphProperties.isEnabled() && graphProperties.getRetrieval().isEnabled();
        boolean graphShadow = graphProperties.getRetrieval().isShadowMode();
        boolean graphAvailable = graphResult.available();
        boolean useGraphInFusion = graphAvailable && !graphShadow;
        double graphWeight = useGraphInFusion ? graphProperties.getRetrieval().getRrfWeight() : 0.0;

        List<VectorChunkResult> rawHits;
        RetrievalCandidateRanker.RankingResult ranking;
        RetrievalDiagnostics diagnostics;

        if (hybridEnabled) {
            Bm25RetrievalService.Bm25SearchResult bm25Result = bm25RetrievalService.search(
                    rewrite.retrievalQuery(), bm25TopK, effectiveFileIds);
            if (bm25Result.available()) {
                long rrfStartNanos = System.nanoTime();
                List<HybridRetrievalCandidate> fused = reciprocalRankFusion.fuseWeighted(
                        denseHits,
                        bm25Result.hits(),
                        graphAvailable ? graphResult.hits() : List.of(),
                        graphAvailable ? graphResult.rawHits() : List.of(),
                        rrfK,
                        ReciprocalRankFusion.DENSE_WEIGHT,
                        ReciprocalRankFusion.BM25_WEIGHT,
                        graphWeight);
                long rrfDurationMs = elapsedMillis(rrfStartNanos);
                rawHits = fused.stream()
                        .map(candidate -> candidate.hit().withSimilarity(candidate.fusionScore()))
                        .toList();
                ranking = candidateRanker.rankHybrid(
                        rewrite.originalQuery(),
                        rewrite.intent(),
                        fused,
                        minSimilarity,
                        filenameMinSimilarity,
                        filenameBoost,
                        maxChunksPerFile,
                        filenameLookupMaxChunks,
                        finalTopK);
                diagnostics = buildDiagnostics(
                        resolveHybridMode(graphAvailable, graphShadow, useGraphInFusion),
                        denseHits.size(),
                        bm25Result.hits().size(),
                        fused.size(),
                        bm25Result.durationMs(),
                        rrfDurationMs,
                        null,
                        graphAttempted,
                        graphAvailable,
                        graphResult,
                        ranking.acceptedHits());
            } else if (useGraphInFusion) {
                long rrfStartNanos = System.nanoTime();
                List<HybridRetrievalCandidate> fused = reciprocalRankFusion.fuseWeighted(
                        denseHits,
                        List.of(),
                        graphResult.hits(),
                        graphResult.rawHits(),
                        rrfK,
                        ReciprocalRankFusion.DENSE_WEIGHT,
                        0.0,
                        graphWeight);
                long rrfDurationMs = elapsedMillis(rrfStartNanos);
                rawHits = fused.stream()
                        .map(candidate -> candidate.hit().withSimilarity(candidate.fusionScore()))
                        .toList();
                ranking = candidateRanker.rankHybrid(
                        rewrite.originalQuery(),
                        rewrite.intent(),
                        fused,
                        minSimilarity,
                        filenameMinSimilarity,
                        filenameBoost,
                        maxChunksPerFile,
                        filenameLookupMaxChunks,
                        finalTopK);
                diagnostics = buildDiagnostics(
                        "VECTOR_GRAPH",
                        denseHits.size(),
                        0,
                        fused.size(),
                        bm25Result.durationMs(),
                        rrfDurationMs,
                        bm25Result.fallbackReason(),
                        graphAttempted,
                        graphAvailable,
                        graphResult,
                        ranking.acceptedHits());
            } else {
                rawHits = denseHits;
                ranking = rankDense(rewrite, denseHits);
                diagnostics = buildDiagnostics(
                        resolveVectorMode(graphAvailable, graphShadow),
                        denseHits.size(),
                        0,
                        0,
                        bm25Result.durationMs(),
                        0,
                        bm25Result.fallbackReason(),
                        graphAttempted,
                        graphAvailable,
                        graphResult,
                        ranking.acceptedHits());
            }
        } else if (useGraphInFusion) {
            long rrfStartNanos = System.nanoTime();
            List<HybridRetrievalCandidate> fused = reciprocalRankFusion.fuseWeighted(
                    denseHits,
                    List.of(),
                    graphResult.hits(),
                    graphResult.rawHits(),
                    rrfK,
                    ReciprocalRankFusion.DENSE_WEIGHT,
                    0.0,
                    graphWeight);
            long rrfDurationMs = elapsedMillis(rrfStartNanos);
            rawHits = fused.stream()
                    .map(candidate -> candidate.hit().withSimilarity(candidate.fusionScore()))
                    .toList();
            ranking = candidateRanker.rankHybrid(
                    rewrite.originalQuery(),
                    rewrite.intent(),
                    fused,
                    minSimilarity,
                    filenameMinSimilarity,
                    filenameBoost,
                    maxChunksPerFile,
                    filenameLookupMaxChunks,
                    finalTopK);
            diagnostics = buildDiagnostics(
                    "VECTOR_GRAPH",
                    denseHits.size(),
                    0,
                    fused.size(),
                    0,
                    rrfDurationMs,
                    null,
                    graphAttempted,
                    graphAvailable,
                    graphResult,
                    ranking.acceptedHits());
        } else {
            rawHits = denseHits;
            ranking = rankDense(rewrite, denseHits);
            diagnostics = buildDiagnostics(
                    resolveVectorMode(graphAvailable, graphShadow),
                    denseHits.size(),
                    0,
                    0,
                    0,
                    0,
                    null,
                    graphAttempted,
                    graphAvailable,
                    graphResult,
                    ranking.acceptedHits());
        }

        List<VectorChunkResult> accepted = ranking.acceptedHits();
        if (log.isInfoEnabled()) {
            log.info(
                    "RAG 检索编排完成 mode={} denseHits={} bm25Hits={} fusedHits={} graphSeeds={} graphHits={} graphShadowOverlap={} rawHits={} deduped={} relevant={} limited={} accepted={} graphDurationMs={} graphFallback={} filenameHints={} effectiveFileIds={} rewriteIntent={} retrievalQuery={}",
                    diagnostics.retrievalMode(),
                    diagnostics.denseHitCount(),
                    diagnostics.bm25HitCount(),
                    diagnostics.fusedHitCount(),
                    diagnostics.graphSeedCount(),
                    diagnostics.graphHitCount(),
                    diagnostics.graphShadowOverlapCount(),
                    ranking.rawHitCount(),
                    ranking.dedupedHitCount(),
                    ranking.relevantHitCount(),
                    ranking.limitedHitCount(),
                    accepted.size(),
                    diagnostics.graphDurationMs(),
                    diagnostics.graphFallbackReason(),
                    rewrite.filenameHints(),
                    effectiveFileIds,
                    rewrite.intent(),
                    rewrite.retrievalQuery());
        }
        return new RetrievalResult(rawHits, accepted, rewrite, effectiveFileIds, diagnostics);
    }

    private static String resolveHybridMode(
            boolean graphAvailable,
            boolean graphShadow,
            boolean useGraphInFusion) {
        if (useGraphInFusion) {
            return "HYBRID_GRAPH";
        }
        if (graphAvailable && graphShadow) {
            return "HYBRID_GRAPH_SHADOW";
        }
        return "HYBRID";
    }

    private static String resolveVectorMode(boolean graphAvailable, boolean graphShadow) {
        if (graphAvailable && graphShadow) {
            return "VECTOR_GRAPH_SHADOW";
        }
        return "VECTOR";
    }

    @SuppressWarnings("java:S107") // Diagnostic fields deliberately map one-to-one to the audit record.
    private static RetrievalDiagnostics buildDiagnostics(
            String retrievalMode,
            int denseHitCount,
            int bm25HitCount,
            int fusedHitCount,
            long bm25DurationMs,
            long rrfDurationMs,
            String bm25FallbackReason,
            boolean graphAttempted,
            boolean graphAvailable,
            GraphRetrievalPort.GraphSearchResult graphResult,
            List<VectorChunkResult> acceptedHits) {
        return new RetrievalDiagnostics(
                retrievalMode,
                denseHitCount,
                bm25HitCount,
                fusedHitCount,
                bm25DurationMs,
                rrfDurationMs,
                bm25FallbackReason,
                graphAttempted,
                graphAvailable,
                graphResult.seedCount(),
                graphResult.hits().size(),
                countGraphShadowOverlap(graphResult.hits(), acceptedHits),
                graphResult.durationMs(),
                graphResult.fallbackReason());
    }

    private static int countGraphShadowOverlap(
            List<VectorChunkResult> graphHits,
            List<VectorChunkResult> acceptedHits) {
        if (graphHits.isEmpty() || acceptedHits.isEmpty()) {
            return 0;
        }
        Set<String> graphKeys = new HashSet<>();
        for (VectorChunkResult hit : graphHits) {
            graphKeys.add(hit.fileId() + "#" + hit.chunkIndex());
        }
        int overlap = 0;
        for (VectorChunkResult hit : acceptedHits) {
            if (graphKeys.contains(hit.fileId() + "#" + hit.chunkIndex())) {
                overlap++;
            }
        }
        return overlap;
    }

    private List<String> resolveEffectiveFileIds(
            QueryRewriteResult rewrite,
            List<String> requestedFileIds) {
        boolean filenameScopedIntent = rewrite.intent() == QueryIntent.FILENAME_LOOKUP
                || rewrite.intent() == QueryIntent.HYBRID;
        if (!filenameScopedIntent || rewrite.filenameHints() == null || rewrite.filenameHints().isEmpty()) {
            return requestedFileIds;
        }
        List<String> resolved = fileEmbeddingService.resolveFileIdsByFilenameHints(
                rewrite.filenameHints(), requestedFileIds);
        if (resolved.isEmpty()) {
            log.info("未解析到明确文件名，保留原检索范围 filenameHints={} requestedFileIds={}",
                    rewrite.filenameHints(), requestedFileIds);
            return requestedFileIds;
        }
        log.info("已根据文件名收窄检索范围 filenameHints={} resolvedFileIds={}",
                rewrite.filenameHints(), resolved);
        return resolved;
    }

    private RetrievalCandidateRanker.RankingResult rankDense(
            QueryRewriteResult rewrite,
            List<VectorChunkResult> denseHits) {
        return candidateRanker.rank(
                rewrite.originalQuery(),
                rewrite.intent(),
                denseHits,
                minSimilarity,
                filenameMinSimilarity,
                filenameBoost,
                maxChunksPerFile,
                filenameLookupMaxChunks,
                finalTopK);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static List<String> normalizeFileIds(List<String> fileIds) {
        if (fileIds == null) {
            return List.of();
        }
        return fileIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static double clampSimilarity(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    public record RetrievalResult(
            List<VectorChunkResult> rawHits,
            List<VectorChunkResult> acceptedHits,
            QueryRewriteResult rewrite,
            List<String> effectiveFileIds,
            RetrievalDiagnostics diagnostics) {
    }

    /** 可持久化的混合检索与图召回执行指标。 */
    public record RetrievalDiagnostics(
            String retrievalMode,
            int denseHitCount,
            int bm25HitCount,
            int fusedHitCount,
            long bm25DurationMs,
            long rrfDurationMs,
            String bm25FallbackReason,
            boolean graphAttempted,
            boolean graphAvailable,
            int graphSeedCount,
            int graphHitCount,
            int graphShadowOverlapCount,
            long graphDurationMs,
            String graphFallbackReason) {

        public static RetrievalDiagnostics skipped() {
            return new RetrievalDiagnostics(
                    "SKIPPED", 0, 0, 0, 0, 0, null,
                    false, false, 0, 0, 0, 0, null);
        }
    }
}
