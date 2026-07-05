package com.example.myllm.harness.adapter.tool.model;

import java.util.List;

public record KnowledgeSearchInput(String query, List<String> fileIds) {
}
