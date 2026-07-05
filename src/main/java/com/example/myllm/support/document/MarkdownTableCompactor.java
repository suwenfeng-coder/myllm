package com.example.myllm.support.document;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩 Maker/Docling 解析出的 Markdown 宽表重复单元格。
 *
 * <p>典型问题：同一单元格文本在列方向重复数十次，导致字符膨胀和清洗删除率异常。</p>
 */
public final class MarkdownTableCompactor {

    private MarkdownTableCompactor() {
    }

    /**
     * 对含 Markdown 表格行的文本做行级压缩；非表格行原样保留。
     */
    public static String compact(String content) {
        if (content == null || content.isBlank()) {
            return content == null ? "" : content;
        }
        String[] lines = content.split("\n", -1);
        StringBuilder result = new StringBuilder(content.length());
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }
            String line = lines[i];
            if (isTableRow(line)) {
                String compacted = compactRow(line);
                if (!compacted.equals(line)) {
                    changed = true;
                }
                result.append(compacted);
            } else {
                result.append(line);
            }
        }
        return changed ? dedupeAdjacentIdenticalRows(result.toString()) : content;
    }

    public static boolean isTableRow(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    public static boolean isSeparatorRow(String line) {
        if (!isTableRow(line)) {
            return false;
        }
        for (String cell : splitCells(line)) {
            String stripped = cell.replace(":", "").strip();
            if (!stripped.isEmpty() && !stripped.chars().allMatch(ch -> ch == '-' || ch == ' ')) {
                return false;
            }
        }
        return true;
    }

    public static List<String> splitCells(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>(parts.length);
        for (String part : parts) {
            cells.add(part.strip());
        }
        return cells;
    }

    static String compactRow(String line) {
        List<String> cells = splitCells(line);
        if (cells.size() <= 1) {
            return line;
        }
        List<String> collapsed = collapseConsecutiveDuplicates(cells);
        if (allCellsEquivalent(cells) && collapsed.size() > 1) {
            collapsed = List.of(cells.get(0));
        }
        if (collapsed.equals(cells)) {
            return line;
        }
        return "| " + String.join(" | ", collapsed) + " |";
    }

    private static List<String> collapseConsecutiveDuplicates(List<String> cells) {
        List<String> result = new ArrayList<>(cells.size());
        String previous = null;
        for (String cell : cells) {
            if (previous != null && previous.equals(cell)) {
                continue;
            }
            result.add(cell);
            previous = cell;
        }
        return result;
    }

    private static boolean allCellsEquivalent(List<String> cells) {
        if (cells.isEmpty()) {
            return false;
        }
        String first = normalizeCell(cells.get(0));
        if (first.isBlank()) {
            return false;
        }
        for (int i = 1; i < cells.size(); i++) {
            if (!first.equals(normalizeCell(cells.get(i)))) {
                return false;
            }
        }
        return cells.size() > 1;
    }

    private static String normalizeCell(String cell) {
        return cell == null ? "" : cell.replaceAll("\\s+", " ").strip();
    }

    private static String dedupeAdjacentIdenticalRows(String content) {
        String[] lines = content.split("\n", -1);
        if (lines.length < 2) {
            return content;
        }
        StringBuilder result = new StringBuilder(content.length());
        String previousNormalized = null;
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String normalized = isTableRow(line) ? normalizeCell(compactRow(line)) : null;
            if (normalized != null && normalized.equals(previousNormalized)) {
                changed = true;
                continue;
            }
            if (i > 0 && !result.isEmpty()) {
                result.append('\n');
            }
            result.append(line);
            previousNormalized = normalized;
        }
        return changed ? result.toString() : content;
    }
}
