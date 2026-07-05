package com.example.myllm.support.document;

import java.util.List;

/**
 * 将结构化文档块序列化为适合审计、分块和向量化的紧凑文本。
 *
 * <p>连续表格行、列表项和短文本使用单换行；较长正文段落和代码块边界
 * 保留一个空行。这样既保留结构，又避免每个 Block 都产生空白行。</p>
 */
public final class DocumentTextRenderer {

    private static final int LONG_PARAGRAPH_LENGTH = 160;

    private DocumentTextRenderer() {
    }

    /**
     * 渲染解析后的清理前文本。只使用单换行连接 Block，避免审计文本在清理前
     * 就被人为注入大量空行。
     */
    public static String renderRaw(List<DocumentBlock> blocks) {
        StringBuilder result = new StringBuilder();
        for (DocumentBlock block : blocks) {
            if (block.content().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(renderBlock(block));
        }
        return result.toString();
    }

    /**
     * 根据相邻 Block 类型选择分隔符，渲染供分块和向量化使用的清理后文本。
     */
    public static String renderCleaned(List<DocumentBlock> blocks) {
        StringBuilder result = new StringBuilder();
        DocumentBlock previous = null;
        for (DocumentBlock block : blocks) {
            if (block.content().isBlank()) {
                continue;
            }
            if (previous != null) {
                result.append(separator(previous, block));
            }
            result.append(renderBlock(block));
            previous = block;
        }
        return result.toString();
    }

    /**
     * 渲染单个 Block，并保留列表、引用、代码和标题的结构标记。
     */
    public static String renderBlock(DocumentBlock block) {
        return switch (block.type()) {
            case HEADING -> "#".repeat(normalizeHeadingLevel(block.headingLevel())) + " " + block.content();
            case LIST_ITEM -> "- " + block.content();
            case QUOTE -> "> " + block.content();
            case CODE -> "```\n" + block.content() + "\n```";
            default -> block.content();
        };
    }

    /**
     * 计算两个相邻 Block 之间的最小必要分隔符。
     */
    public static String separator(DocumentBlock previous, DocumentBlock current) {
        if (previous.type() == DocumentBlockType.CODE || current.type() == DocumentBlockType.CODE) {
            return "\n\n";
        }
        if (previous.type() == DocumentBlockType.PARAGRAPH
                && current.type() == DocumentBlockType.PARAGRAPH
                && previous.content().length() >= LONG_PARAGRAPH_LENGTH
                && current.content().length() >= LONG_PARAGRAPH_LENGTH) {
            return "\n\n";
        }
        return "\n";
    }

    private static int normalizeHeadingLevel(Integer level) {
        return level == null ? 1 : Math.max(1, Math.min(6, level));
    }
}
