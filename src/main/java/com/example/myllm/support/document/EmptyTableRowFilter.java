package com.example.myllm.support.document;

import java.util.List;
import java.util.regex.Pattern;

/** 过滤空表格行与无效图片占位块。 */
public final class EmptyTableRowFilter {

    private static final Pattern IMAGE_ONLY = Pattern.compile("^!\\[[^\\]]*]\\([^)]*\\)$");
    private static final Pattern IMAGE_PLACEHOLDER = Pattern.compile("^<!--\\s*image\\s*-->$", Pattern.CASE_INSENSITIVE);

    private EmptyTableRowFilter() {
    }

    public static boolean isEmptyTableRow(String line) {
        if (line == null || !MarkdownTableCompactor.isTableRow(line)) {
            return false;
        }
        if (MarkdownTableCompactor.isSeparatorRow(line)) {
            return false;
        }
        return MarkdownTableCompactor.splitCells(line).stream().allMatch(String::isBlank);
    }

    public static boolean isImagePlaceholder(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        String trimmed = content.strip();
        return IMAGE_PLACEHOLDER.matcher(trimmed).matches()
                || IMAGE_ONLY.matcher(trimmed).matches()
                || trimmed.startsWith("![](_page_");
    }

    public static boolean shouldRemoveBlock(DocumentBlock block) {
        if (block == null || block.content().isBlank()) {
            return true;
        }
        if (block.type() == DocumentBlockType.TABLE_ROW && isEmptyTableRow(block.content())) {
            return true;
        }
        if ((block.type() == DocumentBlockType.PARAGRAPH || block.type() == DocumentBlockType.TABLE_ROW)
                && isImagePlaceholder(block.content())) {
            return true;
        }
        return false;
    }

    public static int countRemovable(List<DocumentBlock> blocks) {
        int count = 0;
        for (DocumentBlock block : blocks) {
            if (shouldRemoveBlock(block)) {
                count++;
            }
        }
        return count;
    }
}
