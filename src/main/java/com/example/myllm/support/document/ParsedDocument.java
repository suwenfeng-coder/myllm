package com.example.myllm.support.document;

import java.util.List;
import java.util.Map;

/**
 * 文件格式解析后的结构化文档，尚未执行数据清理。
 *
 * @param fileName 原始文件名
 * @param blocks 按源文档顺序排列的结构块
 * @param metadata 文件格式等解析元数据
 */
public record ParsedDocument(
        String fileName,
        List<DocumentBlock> blocks,
        Map<String, Object> metadata) {

    public ParsedDocument {
        fileName = fileName == null ? "unknown" : fileName;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
