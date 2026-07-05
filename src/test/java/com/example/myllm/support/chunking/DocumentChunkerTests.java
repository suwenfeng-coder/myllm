package com.example.myllm.support.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.support.document.CleanedDocument;
import com.example.myllm.support.document.CleaningReport;
import com.example.myllm.support.document.DocumentBlock;
import com.example.myllm.support.document.DocumentBlockType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class DocumentChunkerTests {

    private final RecursiveDocumentChunker recursive =
            new RecursiveDocumentChunker(220, 80, 20, false, 600, 80, 75);

    @Test
    void recursiveChunkerPrefersParagraphAndSentenceBoundaries() {
        String text = "第一段内容。".repeat(30) + "\n\n" + "第二段内容。".repeat(30);

        ChunkingResult result = recursive.chunk(document(
                List.of(new DocumentBlock(DocumentBlockType.PARAGRAPH, text, null, 0)), text));

        assertTrue(result.chunks().size() >= 2);
        assertTrue(result.chunks().stream().allMatch(chunk -> chunk.embeddingContent().length() <= 220));
        assertTrue(result.chunks().get(0).embeddingContent().endsWith("。"));
        assertNotNull(result.chunks().get(0).contentHash());
    }

    @Test
    void structureChunkerPreservesHeadingContextInMetadata() {
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock(DocumentBlockType.HEADING, "安装指南", 1, 0),
                new DocumentBlock(DocumentBlockType.PARAGRAPH, "安装前请检查运行环境。", null, 1),
                new DocumentBlock(DocumentBlockType.HEADING, "数据库", 2, 2),
                new DocumentBlock(DocumentBlockType.TABLE_ROW, "名称 | MySQL", null, 3));
        StructureDocumentChunker chunker = new StructureDocumentChunker(300, 50, recursive);

        ChunkingResult result = chunker.chunk(document(blocks, "安装指南 数据库"));

        assertEquals(ChunkStrategy.DOCUMENT, result.appliedStrategy());
        assertTrue(result.chunks().stream().anyMatch(chunk -> "安装指南 > 数据库".equals(chunk.headingPath())));
        assertTrue(result.chunks().stream().anyMatch(chunk -> chunk.embeddingContent().contains("名称 | MySQL")));
        assertTrue(result.chunks().stream().noneMatch(chunk -> chunk.embeddingContent().contains("章节路径：")));
        assertTrue(result.chunks().stream().anyMatch(chunk -> chunk.displayText().contains("章节路径：安装指南 > 数据库")));
    }

    @Test
    void semanticChunkerBreaksWhenAdjacentTopicsChange() {
        String text = "苹果是一种水果，富含维生素。苹果可以制作果汁和甜点。"
                + "数据库用于持久化数据。数据库索引可以提升查询速度。"
                + "事务能够保证数据库写入的一致性。";
        SemanticDocumentChunker chunker = new SemanticDocumentChunker(
                new TopicEmbeddingModel(), recursive, 20, 20, 300, 8, 25);

        ChunkingResult result = chunker.chunk(document(
                List.of(new DocumentBlock(DocumentBlockType.PARAGRAPH, text, null, 0)), text));

        assertEquals(ChunkStrategy.SEMANTIC, result.appliedStrategy());
        assertTrue(result.chunks().size() >= 2);
        assertTrue(result.chunks().get(0).embeddingContent().contains("苹果"));
        assertTrue(result.chunks().stream().anyMatch(chunk -> chunk.embeddingContent().contains("数据库")));
    }

    @Test
    void smartChunkerRoutesStructuredDocumentToDocumentStrategy() {
        StructureDocumentChunker structure = new StructureDocumentChunker(300, 50, recursive);
        SemanticDocumentChunker semantic = new SemanticDocumentChunker(
                new TopicEmbeddingModel(), recursive, 20, 20, 300, 8, 25);
        SmartDocumentChunker smart = new SmartDocumentChunker(structure, semantic, recursive, 500);
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock(DocumentBlockType.HEADING, "标题", 1, 0),
                new DocumentBlock(DocumentBlockType.PARAGRAPH, "正文内容足够用于分块。", null, 1));

        ChunkingResult result = smart.chunk(document(blocks, "# 标题\n\n正文内容足够用于分块。"));

        assertEquals(ChunkStrategy.SMART, result.requestedStrategy());
        assertEquals(ChunkStrategy.DOCUMENT, result.appliedStrategy());
    }

    @Test
    void documentChunkFactoryComputesStableContentHash() {
        DocumentChunk first = DocumentChunkFactory.of(0, "相同内容", null, null, null, Map.of());
        DocumentChunk second = DocumentChunkFactory.of(1, "相同内容", null, null, null, Map.of());

        assertEquals(first.contentHash(), second.contentHash());
        assertTrue(first.charCount() > 0);
        assertTrue(first.tokenCount() > 0);
    }

    @Test
    void finalWhitespaceNormalizerCompactsBlankLinesAndPreservesCodeIndentation() {
        String input = "标题  \n\n\n\n正文   \n\n```java\n  int value = 1;  \n```\n\n\n结尾";

        String normalized = ChunkWhitespaceNormalizer.normalize(input);

        assertFalse(normalized.contains("\n\n\n"));
        assertTrue(normalized.contains("\n  int value = 1;\n"));
        assertFalse(normalized.contains("标题  \n"));
    }

    private static CleanedDocument document(List<DocumentBlock> blocks, String content) {
        CleaningReport report = new CleaningReport(
                "test", content.length(), content.length(), 0, 0.0, 0, Map.of(), List.of());
        return new CleanedDocument(blocks, content, content, report);
    }

    private static final class TopicEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("测试不使用 call");
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(TopicEmbeddingModel::vector).toList();
        }

        private static float[] vector(String text) {
            return text.contains("苹果") ? new float[] {1.0f, 0.0f} : new float[] {0.0f, 1.0f};
        }
    }
}
