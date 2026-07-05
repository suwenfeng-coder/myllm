package com.example.myllm.harness.adapter.tool.model;

import java.util.List;

public record FileListOutput(List<FileListItem> files, int totalCount) {
}
