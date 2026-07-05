package com.example.myllm.support.document;

import java.util.List;

/**
 * 数据清理完成后的文档。
 *
 * @param blocks 清理后的结构块
 * @param rawContent 解析后、清理前的可读文本，用于审计
 * @param content 清理后供分块和向量化使用的文本
 * @param report 清理统计报告
 */
public record CleanedDocument(
        List<DocumentBlock> blocks,
        String rawContent,
        String content,
        CleaningReport report) {

    public CleanedDocument {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        rawContent = rawContent == null ? "" : rawContent;
        content = content == null ? "" : content;
    }
}
