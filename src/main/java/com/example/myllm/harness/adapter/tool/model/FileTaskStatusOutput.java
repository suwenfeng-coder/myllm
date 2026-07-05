package com.example.myllm.harness.adapter.tool.model;

public record FileTaskStatusOutput(
        String taskId,
        String status,
        String phase,
        int percent,
        String message,
        String fileId,
        String errorMessage,
        boolean found) {
}
