package com.example.myllm.support.document;

/**
 * 保留文档结构和原始顺序的最小解析单元。
 *
 * @param type Block 类型
 * @param content 文本内容
 * @param headingLevel 标题层级，非标题时为空
 * @param sourceIndex 在源文档解析结果中的顺序
 */
public record DocumentBlock(
        DocumentBlockType type,
        String content,
        Integer headingLevel,
        int sourceIndex) {

    public DocumentBlock {
        type = type == null ? DocumentBlockType.PARAGRAPH : type;
        content = content == null ? "" : content;
    }
}
