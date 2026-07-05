package com.example.myllm.harness.adapter.tool.model;

import java.time.LocalDateTime;

public record FileListItem(
        String fileId,
        String fileName,
        String contentType,
        int chunkCount,
        LocalDateTime createdAt) {
}
