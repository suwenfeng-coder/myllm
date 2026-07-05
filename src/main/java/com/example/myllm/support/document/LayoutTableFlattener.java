package com.example.myllm.support.document;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 PDF 版面布局型表格（左标签右正文）降级为段落，保留真实数据表。
 */
public final class LayoutTableFlattener {

    private LayoutTableFlattener() {
    }

    public static List<DocumentBlock> flattenBlocks(List<DocumentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<DocumentBlock> result = new ArrayList<>(blocks.size());
        int index = 0;
        while (index < blocks.size()) {
            DocumentBlock block = blocks.get(index);
            if (block.type() != DocumentBlockType.TABLE_ROW) {
                result.add(block);
                index++;
                continue;
            }
            int start = index;
            while (index < blocks.size() && blocks.get(index).type() == DocumentBlockType.TABLE_ROW) {
                index++;
            }
            List<DocumentBlock> segment = blocks.subList(start, index);
            if (!isMarkdownTableSegment(segment) || isDataTable(segment) || !isLayoutTable(segment)) {
                result.addAll(segment);
            } else {
                result.addAll(flattenLayoutSegment(segment));
            }
        }
        return result;
    }

    static boolean isDataTable(List<DocumentBlock> rows) {
        int structuredRows = 0;
        int wideDataRows = 0;
        for (DocumentBlock row : rows) {
            String content = row.content();
            if (MarkdownTableCompactor.isSeparatorRow(content)) {
                return true;
            }
            List<String> cells = MarkdownTableCompactor.splitCells(content);
            long nonEmpty = cells.stream().filter(cell -> !cell.isBlank()).count();
            if (nonEmpty >= 4) {
                wideDataRows++;
            }
            String joined = String.join("", cells);
            if (joined.contains("序号") && joined.contains("名称")) {
                return true;
            }
            if (joined.contains("条目号") && joined.contains("需求规格")) {
                return true;
            }
            if (nonEmpty >= 3) {
                structuredRows++;
            }
        }
        return wideDataRows >= 2 || structuredRows >= Math.max(2, rows.size() / 3) || hasSingleWideDataRow(rows);
    }

    private static boolean hasSingleWideDataRow(List<DocumentBlock> rows) {
        if (rows.size() > 2) {
            return false;
        }
        for (DocumentBlock row : rows) {
            long nonEmpty = MarkdownTableCompactor.splitCells(row.content()).stream()
                    .filter(cell -> !cell.isBlank())
                    .count();
            if (nonEmpty >= 4) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMarkdownTableSegment(List<DocumentBlock> rows) {
        long markdownRows = rows.stream()
                .filter(row -> MarkdownTableCompactor.isTableRow(row.content()))
                .count();
        return markdownRows * 2 >= rows.size();
    }

    private static boolean isLayoutTable(List<DocumentBlock> rows) {
        int twoColumnRows = 0;
        int wideRows = 0;
        for (DocumentBlock row : rows) {
            if (MarkdownTableCompactor.isSeparatorRow(row.content())) {
                continue;
            }
            List<String> cells = MarkdownTableCompactor.splitCells(row.content());
            long nonEmpty = cells.stream().filter(cell -> !cell.isBlank()).count();
            if (nonEmpty >= 4) {
                wideRows++;
            }
            if (nonEmpty == 2) {
                twoColumnRows++;
            }
        }
        if (wideRows > 0) {
            return false;
        }
        return twoColumnRows >= 2 && twoColumnRows * 2 >= rows.size();
    }

    static List<DocumentBlock> flattenLayoutSegment(List<DocumentBlock> rows) {
        List<DocumentBlock> flattened = new ArrayList<>(rows.size());
        for (DocumentBlock row : rows) {
            String content = row.content();
            if (MarkdownTableCompactor.isSeparatorRow(content)
                    || EmptyTableRowFilter.isEmptyTableRow(content)) {
                continue;
            }
            List<String> cells = MarkdownTableCompactor.splitCells(content);
            List<String> nonEmpty = cells.stream().filter(cell -> !cell.isBlank()).toList();
            if (nonEmpty.isEmpty()) {
                continue;
            }
            if (nonEmpty.size() == 1 && cells.size() >= 2) {
                continue;
            }
            String text = toParagraphText(nonEmpty);
            if (text.isBlank()) {
                continue;
            }
            flattened.add(new DocumentBlock(
                    DocumentBlockType.PARAGRAPH, text, null, row.sourceIndex()));
        }
        return flattened;
    }

    private static String toParagraphText(List<String> nonEmpty) {
        if (nonEmpty.size() == 1) {
            return nonEmpty.get(0);
        }
        String label = nonEmpty.get(0);
        String value = String.join(" ", nonEmpty.subList(1, nonEmpty.size()));
        if (label.length() <= 18 && !label.endsWith("。") && !label.endsWith("：") && !label.endsWith(":")) {
            return label + ": " + value;
        }
        return String.join(" ", nonEmpty);
    }
}
