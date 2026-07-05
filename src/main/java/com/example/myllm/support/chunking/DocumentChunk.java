package com.example.myllm.support.chunking;

import java.util.Map;

/**
 * 带元数据的文档分块，供向量化入库与检索展示使用。
 *
 * @param index 在文档内的顺序，从 0 开始
 * @param embeddingContent 送入 embedding 模型的纯文本（不含章节路径前缀）
 * @param displayText 展示与拼 Prompt 用的文本
 * @param headingPath 章节路径，如「安装指南 &gt; 数据库」
 * @param charCount Unicode 字符数
 * @param tokenCount Token 估算值
 * @param contentHash 基于 embeddingContent 的标准化 SHA-256
 * @param startSourceIndex 覆盖的首个 DocumentBlock.sourceIndex
 * @param endSourceIndex 覆盖的末个 DocumentBlock.sourceIndex
 * @param metadata 扩展元数据（blockTypes 等）
 */
public record DocumentChunk(
        int index,
        String embeddingContent,
        String displayText,
        String headingPath,
        int charCount,
        int tokenCount,
        String contentHash,
        Integer startSourceIndex,
        Integer endSourceIndex,
        Map<String, Object> metadata) {

    public DocumentChunk {
        embeddingContent = embeddingContent == null ? "" : embeddingContent;
        displayText = displayText == null ? "" : displayText;
        headingPath = headingPath == null || headingPath.isBlank() ? null : headingPath;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
