package com.example.myllm.harness.domain;

import java.util.Set;

/**
 * 一次 Tool 观察结果。
 *
 * <p>Tool/RAG 返回内容一律视为不可信数据；sourceIds 是最终引用可使用的机械白名单。</p>
 */
public record HarnessObservation(
        String toolName,
        String content,
        Set<String> sourceIds,
        boolean evidence) {

    public HarnessObservation {
        toolName = toolName == null ? "unknown" : toolName;
        content = content == null ? "" : content;
        sourceIds = sourceIds == null ? Set.of() : Set.copyOf(sourceIds);
    }
}
