package com.example.myllm.support.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 解析后 Block 列表的结构化归一化入口。 */
public final class DocumentBlockNormalizer {

    private DocumentBlockNormalizer() {
    }

    public static List<DocumentBlock> normalize(List<DocumentBlock> blocks, Map<String, Integer> affected) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<DocumentBlock> current = new ArrayList<>(blocks.size());
        for (DocumentBlock block : blocks) {
            if (EmptyTableRowFilter.shouldRemoveBlock(block)) {
                continue;
            }
            current.add(normalizeBlock(block, affected));
        }
        if (current.size() < blocks.size()) {
            affected.merge("empty-or-image-block-removed", blocks.size() - current.size(), Integer::sum);
        }

        List<DocumentBlock> flattened = LayoutTableFlattener.flattenBlocks(current);
        if (flattened.size() != current.size()) {
            affected.merge("layout-table-flattened", Math.max(0, current.size() - flattened.size()), Integer::sum);
        }

        List<DocumentBlock> headings = HeadingLevelRepairer.repair(flattened);
        if (headings.size() != flattened.size()) {
            affected.merge("duplicate-heading-removed", Math.max(0, flattened.size() - headings.size()), Integer::sum);
        }
        return headings;
    }

    private static DocumentBlock normalizeBlock(DocumentBlock block, Map<String, Integer> affected) {
        String content = block.content();
        String merged = HtmlLineBreakMerger.merge(content);
        if (!merged.equals(content)) {
            affected.merge("html-br-merged", 1, Integer::sum);
            content = merged;
        }
        String checkboxes = FormCheckboxNormalizer.normalize(content);
        if (!checkboxes.equals(content)) {
            affected.merge("form-checkbox-normalized", 1, Integer::sum);
            content = checkboxes;
        }
        if (block.type() == DocumentBlockType.TABLE_ROW
                || (block.type() == DocumentBlockType.PARAGRAPH && content.contains("|"))) {
            String compacted = MarkdownTableCompactor.compact(content);
            if (!compacted.equals(content)) {
                affected.merge("table-column-compacted", 1, Integer::sum);
                content = compacted;
            }
        }
        if (content.equals(block.content())) {
            return block;
        }
        return new DocumentBlock(block.type(), content, block.headingLevel(), block.sourceIndex());
    }
}
