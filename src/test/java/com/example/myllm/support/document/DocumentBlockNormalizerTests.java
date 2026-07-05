package com.example.myllm.support.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentBlockNormalizerTests {

    @Test
    void mergesBrTagsInTableRows() {
        ParsedDocument document = new ParsedDocument(
                "maker.pdf",
                List.of(new DocumentBlock(
                        DocumentBlockType.TABLE_ROW,
                        "| 澄清截止 | 2024<br>年<br>6<br>月<br>28<br>日 |",
                        null,
                        0)),
                Map.of());
        Map<String, Integer> affected = new LinkedHashMap<>();
        List<DocumentBlock> normalized = DocumentBlockNormalizer.normalize(document.blocks(), affected);

        assertEquals(1, normalized.size());
        assertTrue(normalized.get(0).content().contains("2024 年 6 月 28 日"));
        assertFalse(normalized.get(0).content().contains("<br>"));
        assertTrue(affected.getOrDefault("html-br-merged", 0) > 0);
    }

    @Test
    void flattensTwoColumnLayoutTablesToParagraphs() {
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock(DocumentBlockType.TABLE_ROW, "| 四、 | 澄清截止期限及要求 |", null, 0),
                new DocumentBlock(DocumentBlockType.TABLE_ROW, "| 澄清截止 | 2024年6月28日16:00前 |", null, 1),
                new DocumentBlock(DocumentBlockType.TABLE_ROW, "| 期限 | |", null, 2));
        Map<String, Integer> affected = new LinkedHashMap<>();

        List<DocumentBlock> normalized = DocumentBlockNormalizer.normalize(blocks, affected);

        assertTrue(normalized.stream().allMatch(block -> block.type() == DocumentBlockType.PARAGRAPH));
        assertTrue(normalized.stream().anyMatch(block -> block.content().contains("澄清截止期限及要求")));
        assertTrue(normalized.stream().anyMatch(block -> block.content().contains("2024年6月28日16:00前")));
        assertFalse(normalized.stream().anyMatch(block -> block.content().equals("期限")));
    }

    @Test
    void keepsStructuredDataTables() {
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock(DocumentBlockType.TABLE_ROW,
                        "| 序号 | 标的名称 | 品目分类编码 | 计量单位 | 数量 |", null, 0),
                new DocumentBlock(DocumentBlockType.TABLE_ROW,
                        "|----|------|------|------|----|", null, 1),
                new DocumentBlock(DocumentBlockType.TABLE_ROW,
                        "| 1 | 金融业综合统计服务 | C99 | 项 | 1 |", null, 2));
        Map<String, Integer> affected = new LinkedHashMap<>();

        List<DocumentBlock> normalized = DocumentBlockNormalizer.normalize(blocks, affected);

        assertEquals(3, normalized.size());
        assertTrue(normalized.stream().allMatch(block -> block.type() == DocumentBlockType.TABLE_ROW));
    }

    @Test
    void removesDuplicateHeadingsAndRepairsChapterLevel() {
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock(DocumentBlockType.HEADING, "第一章 磋商邀请", 2, 0),
                new DocumentBlock(DocumentBlockType.HEADING, "磋商邀请", 2, 1),
                new DocumentBlock(DocumentBlockType.HEADING, "磋商邀请", 2, 2),
                new DocumentBlock(DocumentBlockType.HEADING, "格式4", 2, 3));
        Map<String, Integer> affected = new LinkedHashMap<>();

        List<DocumentBlock> normalized = DocumentBlockNormalizer.normalize(blocks, affected);

        assertEquals(3, normalized.size());
        assertEquals(1, normalized.get(0).headingLevel());
        assertEquals(2, normalized.get(2).headingLevel());
    }

    @Test
    void removesImagePlaceholders() {
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock(DocumentBlockType.PARAGRAPH, "<!-- image -->", null, 0),
                new DocumentBlock(DocumentBlockType.PARAGRAPH, "![](_page_0_Picture_2.jpeg)", null, 1),
                new DocumentBlock(DocumentBlockType.PARAGRAPH, "有效正文", null, 2));
        Map<String, Integer> affected = new LinkedHashMap<>();

        List<DocumentBlock> normalized = DocumentBlockNormalizer.normalize(blocks, affected);

        assertEquals(1, normalized.size());
        assertEquals("有效正文", normalized.get(0).content());
    }
}
