package com.example.myllm.dto;

/** Neo4j 图能力健康和功能开关状态。 */
public record GraphHealthResponse(
        boolean available,
        String version,
        String edition,
        String database,
        boolean indexingEnabled,
        boolean retrievalEnabled,
        boolean shadowMode,
        String error) {
}
