package com.example.myllm.support.document;

/**
 * 解析阶段识别出的文档结构块类型。
 */
public enum DocumentBlockType {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TABLE_ROW,
    CODE,
    QUOTE
}
