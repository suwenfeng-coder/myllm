package com.example.myllm.harness.adapter.tool.model;

import java.time.LocalDateTime;

public record GraphStatusOutput(
        String fileId,
        boolean found,
        String operation,
        String status,
        int attemptCount,
        int nodeCount,
        int relationshipCount,
        String errorMessage,
        LocalDateTime finishedAt) {
}
