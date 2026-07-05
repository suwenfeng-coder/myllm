package com.example.myllm.service;

import com.example.myllm.dto.VectorChunkResult;
import com.example.myllm.support.graph.GraphRetrievalHit;
import java.util.List;

/**
 * 图召回端口：Neo4j 不可用时由降级实现返回 {@code available=false}。
 */
public interface GraphRetrievalPort {

    GraphSearchResult search(String query, List<String> fileIds);

    /** 图召回执行结果，失败不抛异常，由调用方降级。 */
    record GraphSearchResult(
            boolean available,
            List<VectorChunkResult> hits,
            List<GraphRetrievalHit> rawHits,
            int seedCount,
            long durationMs,
            String fallbackReason) {

        public GraphSearchResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
            rawHits = rawHits == null ? List.of() : List.copyOf(rawHits);
        }

        public static GraphSearchResult unavailable(String reason) {
            return new GraphSearchResult(false, List.of(), List.of(), 0, 0, reason);
        }

        public static GraphSearchResult success(
                List<VectorChunkResult> hits,
                List<GraphRetrievalHit> rawHits,
                int seedCount,
                long durationMs) {
            return new GraphSearchResult(true, hits, rawHits, seedCount, durationMs, null);
        }
    }
}
