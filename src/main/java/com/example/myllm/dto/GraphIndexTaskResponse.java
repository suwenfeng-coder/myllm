package com.example.myllm.dto;

import java.time.LocalDateTime;

/** 文件对应的最近一笔 Neo4j 构图任务状态。 */
public record GraphIndexTaskResponse(
        Long id,
        String fileId,
        String operation,
        String status,
        String extractionVersion,
        int attemptCount,
        int nodeCount,
        int relationshipCount,
        String errorMessage,
        LocalDateTime nextRetryAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime updatedAt) {
}
