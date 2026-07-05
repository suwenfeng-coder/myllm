package com.example.myllm.harness.adapter.tool.model;

import java.util.List;

public record KnowledgeSearchCitation(
        String fileId,
        String fileName,
        int chunkIndex,
        double score,
        String headingPath,
        String snippet) {
}
