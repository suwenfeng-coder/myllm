package com.example.myllm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.support.FileTextExtractor;
import com.example.myllm.support.document.CleanedDocument;
import com.example.myllm.support.document.DocumentBlock;
import com.example.myllm.support.document.DocumentBlockType;
import com.example.myllm.support.document.ParsedDocument;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.mock.web.MockMultipartFile;

class DocumentCleaningServiceTests {

    private final DocumentCleaningService service =
            new DocumentCleaningService("test-v1", 10, 0.90);

    @Test
    void cleansConservativeNoiseAndRemovesExactDuplicateParagraphs() {
        String repeated = "这是一个长度足够的完全重复业务段落，用于验证保守去重规则。";
        ParsedDocument document = new ParsedDocument(
                "sample.txt",
                List.of(
                        new DocumentBlock(DocumentBlockType.HEADING, "  标题\u00a0一  ", 1, 0),
                        new DocumentBlock(DocumentBlockType.PARAGRAPH, repeated + "\u200b", null, 1),
                        new DocumentBlock(DocumentBlockType.PARAGRAPH, repeated, null, 2),
                        new DocumentBlock(DocumentBlockType.CODE, "  int x = 1;  ", null, 3)),
                Map.of());

        CleanedDocument result = service.clean(document);

        assertTrue(result.content().contains("# 标题 一"));
        assertFalse(result.content().contains("\u200b"));
        assertTrue(result.content().contains("  int x = 1;  "));
        assertEquals(1, result.report().removedDuplicateBlocks());
        assertEquals("test-v1", result.report().cleanerVersion());
    }

    @Test
    void rejectsContentBelowQualityThreshold() {
        ParsedDocument document = new ParsedDocument(
                "short.txt",
                List.of(new DocumentBlock(DocumentBlockType.PARAGRAPH, "短文", null, 0)),
                Map.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> service.clean(document));

        assertTrue(error.getMessage().contains("有效内容过少"));
    }

    @Test
    void markdownParserPreservesHeadingListAndCodeStructure() {
        String markdown = """
                # 使用说明

                这是正文内容。

                - 第一项

                ```java
                  int value = 1;
                ```
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.md", "text/markdown", markdown.getBytes(StandardCharsets.UTF_8));

        ParsedDocument parsed = FileTextExtractor.extractDocument(file);

        assertEquals(DocumentBlockType.HEADING, parsed.blocks().get(0).type());
        assertTrue(parsed.blocks().stream().anyMatch(block -> block.type() == DocumentBlockType.LIST_ITEM));
        assertTrue(parsed.blocks().stream().anyMatch(block -> block.type() == DocumentBlockType.CODE));
    }

    @Test
    void mergesBrTagsDuringCleaning() {
        ParsedDocument document = new ParsedDocument(
                "maker.pdf",
                List.of(new DocumentBlock(
                        DocumentBlockType.PARAGRAPH,
                        "金额13717.0<br>7<br>万元，截止2024<br>年<br>6<br>月<br>28<br>日",
                        null,
                        0)),
                Map.of());

        CleanedDocument result = service.clean(document);

        assertTrue(result.content().contains("13717.0 7 万元"));
        assertTrue(result.content().contains("2024 年 6 月 28 日"));
        assertFalse(result.content().contains("<br>"));
    }

    @Test
    void compactsRepeatedTableColumnsDuringCleaning() {
        String wideRow = "| 呆账 | 呆账 | 呆账 | 呆账 | 核销 | 核销 | 核销 |";
        ParsedDocument document = new ParsedDocument(
                "table.pdf",
                List.of(
                        new DocumentBlock(DocumentBlockType.HEADING, "参数表", 1, 0),
                        new DocumentBlock(DocumentBlockType.TABLE_ROW, wideRow, null, 1)),
                Map.of());

        CleanedDocument result = service.clean(document);

        assertTrue(result.content().contains("| 呆账 | 核销 |"));
        assertFalse(result.content().contains("呆账 | 呆账 | 呆账"));
    }

    @Test
    void cleanedRendererUsesSingleLineBreakForTableRowsAndShortBlocks() {
        ParsedDocument document = new ParsedDocument(
                "table.docx",
                List.of(
                        new DocumentBlock(DocumentBlockType.HEADING, "参数表", 1, 0),
                        new DocumentBlock(DocumentBlockType.TABLE_ROW, "字段 | 类型", null, 1),
                        new DocumentBlock(DocumentBlockType.TABLE_ROW, "name | varchar", null, 2),
                        new DocumentBlock(DocumentBlockType.LIST_ITEM, "第一项", null, 3),
                        new DocumentBlock(DocumentBlockType.LIST_ITEM, "第二项", null, 4)),
                Map.of());

        CleanedDocument result = service.clean(document);

        assertTrue(result.content().contains("字段 | 类型\nname | varchar"));
        assertTrue(result.content().contains("- 第一项\n- 第二项"));
        assertFalse(result.content().contains("字段 | 类型\n\nname | varchar"));
    }

    @Test
    void docxParserRecognizesOutlineHeadingLevel() throws Exception {
        byte[] content;
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph heading = document.createParagraph();
            heading.createRun().setText("数据库设计");
            heading.getCTP().addNewPPr().addNewOutlineLvl().setVal(BigInteger.ONE);
            document.createParagraph().createRun().setText("正文内容用于验证标题解析。");
            document.write(output);
            content = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "outline.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content);

        ParsedDocument parsed = FileTextExtractor.extractDocument(file);

        assertEquals(DocumentBlockType.HEADING, parsed.blocks().get(0).type());
        assertEquals(2, parsed.blocks().get(0).headingLevel());
    }

    @Test
    void docxExtensionWithPlainTextContentFallsBackToParagraphParser() {
        String text = "这是一段以 .docx 为后缀保存的纯文本内容，用于验证格式探测回退。";
        MockMultipartFile file = new MockMultipartFile(
                "file", "plain.docx", "application/octet-stream", text.getBytes(StandardCharsets.UTF_8));

        ParsedDocument parsed = FileTextExtractor.extractDocument(file);

        assertEquals("PLAIN_TEXT", parsed.metadata().get("detectedFormat"));
        assertTrue(parsed.blocks().stream().anyMatch(block -> block.content().contains("纯文本内容")));
    }

    @Test
    void rejectsWordLockFiles() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "~$report.docx", "application/octet-stream", "x".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> FileTextExtractor.extractDocument(file));

        assertTrue(error.getMessage().contains("临时锁文件"));
    }

    @Test
    void rejectsInvalidDocxWithActionableMessage() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "broken.docx", "application/octet-stream", new byte[] {0x01, 0x02, 0x03, 0x04, 0x05});

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> FileTextExtractor.extractDocument(file));

        assertTrue(error.getMessage().contains(".docx"));
    }
}
