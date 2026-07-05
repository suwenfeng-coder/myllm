package com.example.myllm.support.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkdownTableCompactorTests {

    @Test
    void collapsesRepeatedColumnsInWideTableRow() {
        String row = "| 呆账 | 呆账 | 呆账 | 呆账 | 核销 | 核销 | 核销 |";

        String compacted = MarkdownTableCompactor.compact(row);

        assertEquals("| 呆账 | 核销 |", compacted);
    }

    @Test
    void collapsesConsecutiveDuplicateCellsOnly() {
        String row = "| A | A | B | B | B | C |";

        String compacted = MarkdownTableCompactor.compactRow(row);

        assertEquals("| A | B | C |", compacted);
    }

    @Test
    void leavesNormalTableRowsUntouched() {
        String row = "| 字段 | 类型 | 说明 |";

        assertEquals(row, MarkdownTableCompactor.compact(row));
    }

    @Test
    void compactsMultilineTableBlocks() {
        String table = """
                | 名称 | 名称 | 名称 |
                | 类型 | 类型 | 类型 |
                """;

        String compacted = MarkdownTableCompactor.compact(table);

        assertTrue(compacted.contains("| 名称 |"));
        assertTrue(compacted.contains("| 类型 |"));
        assertFalse(compacted.contains("名称 | 名称"));
    }
}
