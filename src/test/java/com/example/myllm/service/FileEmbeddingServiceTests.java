package com.example.myllm.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.myllm.config.GraphProperties;
import com.example.myllm.support.chunking.ChunkStrategy;
import com.example.myllm.support.chunking.ChunkingResult;
import com.example.myllm.support.chunking.DocumentChunk;
import com.example.myllm.support.chunking.DocumentChunkFactory;
import com.example.myllm.support.document.CleanedDocument;
import com.example.myllm.support.document.CleaningReport;
import com.example.myllm.support.document.DocumentBlock;
import com.example.myllm.support.document.DocumentBlockType;
import com.example.myllm.support.document.DocumentParseResult;
import com.example.myllm.support.document.ParseProgressListener;
import com.example.myllm.support.document.ParsedDocument;
import com.example.myllm.support.upload.UploadProgressReporter;
import com.example.myllm.testing.LogCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

class FileEmbeddingServiceTests {

    @Test
    void embeddingCompletionLogOmitsExternalDocumentData() {
        String content = "可向量化正文";
        DocumentBlock block = new DocumentBlock(
                DocumentBlockType.PARAGRAPH, content, null, 0);
        ParsedDocument parsedDocument = new ParsedDocument(
                "secret-file.pdf",
                List.of(block),
                Map.of("extension", "secret-content-type"));
        DocumentParseResult parseResult = new DocumentParseResult(
                parsedDocument,
                "auto",
                "docling",
                "secret-parser-engine",
                1,
                12L,
                List.of("docling"),
                null);
        CleaningReport cleaningReport = new CleaningReport(
                "test-cleaner",
                content.length(),
                content.length(),
                0,
                0.0,
                0,
                Map.of(),
                List.of("secret-cleaning-warning"));
        CleanedDocument cleanedDocument = new CleanedDocument(
                List.of(block), content, content, cleaningReport);
        DocumentChunk chunk = DocumentChunkFactory.of(
                0, content, "secret-heading", 0, 0, Map.of());
        ChunkingResult chunkingResult = new ChunkingResult(
                ChunkStrategy.FIXED, ChunkStrategy.FIXED, List.of(chunk));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "secret-file.pdf",
                "application/pdf",
                content.getBytes(StandardCharsets.UTF_8));

        JdbcTemplate vectorJdbcTemplate = mock(JdbcTemplate.class);
        DocumentCleaningService cleaningService = mock(DocumentCleaningService.class);
        DocumentCleaningLogService cleaningLogService = mock(DocumentCleaningLogService.class);
        DocumentChunkingService chunkingService = mock(DocumentChunkingService.class);
        DocumentParseService parseService = mock(DocumentParseService.class);
        DocumentStorageService storageService = mock(DocumentStorageService.class);
        VectorChunkBatchWriter batchWriter = mock(VectorChunkBatchWriter.class);

        when(parseService.parse(
                eq(file),
                eq("auto"),
                any(ParseProgressListener.class)))
                .thenReturn(parseResult);
        when(storageService.isEnabled()).thenReturn(false);
        when(cleaningService.clean(parsedDocument)).thenReturn(cleanedDocument);
        when(chunkingService.chunk(cleanedDocument, "fixed")).thenReturn(chunkingResult);
        when(batchWriter.writeAll(
                any(VectorChunkBatchWriter.WriteRequest.class),
                eq(List.of(chunk)),
                any(VectorChunkBatchWriter.ProgressCallback.class)))
                .thenReturn(3);

        FileEmbeddingService service = new FileEmbeddingService(
                vectorJdbcTemplate,
                mock(EmbeddingModel.class),
                cleaningService,
                cleaningLogService,
                chunkingService,
                parseService,
                storageService,
                mock(GraphIndexTaskService.class),
                new GraphProperties(),
                batchWriter,
                mock(FileIngestionCleanupService.class),
                new ObjectMapper(),
                "file_embeddings",
                5,
                false,
                16,
                64);

        try (LogCapture logs = LogCapture.forClass(FileEmbeddingService.class)) {
            service.embedAndStoreWithProgress(
                    file, "fixed", "auto", UploadProgressReporter.noop());

            String completionLog = logs.eventStartingWith("文件清理及向量化完成")
                    .getFormattedMessage();
            for (String sensitiveValue : List.of(
                    "secret-file.pdf",
                    "secret-content-type",
                    "secret-parser-engine",
                    "secret-heading",
                    "secret-cleaning-warning")) {
                assertFalse(
                        completionLog.contains(sensitiveValue),
                        () -> "完成日志仍包含外部数据: " + sensitiveValue);
            }
        }
    }
}
