package com.example.myllm.dto;

/** 文档上传流水线各阶段耗时（毫秒）。 */
public record IngestPipelineTiming(
        Long storingDurationMs,
        Long cleaningDurationMs,
        Long chunkingDurationMs,
        Long embeddingDurationMs,
        Long pipelineDurationMs) {
}
