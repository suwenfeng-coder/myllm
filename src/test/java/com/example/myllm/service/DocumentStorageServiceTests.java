package com.example.myllm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.myllm.config.MinioProperties;
import com.example.myllm.entity.DocumentStorageLog;
import com.example.myllm.repository.DocumentStorageLogRepository;
import com.example.myllm.support.document.DocumentParseResult;
import com.example.myllm.support.document.ParsedDocument;
import com.example.myllm.support.minio.MinioStorageService;
import com.example.myllm.support.minio.MinioStoredObject;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DocumentStorageServiceTests {

    private static final String FILE_ID = "550e8400-e29b-41d4-a716-446655440000";

    private DocumentStorageLogRepository repository;
    private MinioStorageService minioStorageService;
    private DocumentStorageService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentStorageLogRepository.class);
        minioStorageService = mock(MinioStorageService.class);
        MinioProperties properties = new MinioProperties(
                true,
                "http://127.0.0.1:9000",
                "access",
                "secret",
                "myllm",
                "uploads",
                "parsed",
                false);
        service = new DocumentStorageService(repository, Optional.of(minioStorageService), properties);
    }

    @Test
    void normalizesOneFileIdForBothWritesAndAudit() {
        MockMultipartFile file = sampleFile();
        DocumentParseResult parseResult = sampleParseResult();
        MinioStoredObject original = new MinioStoredObject(
                "myllm", "uploads/2026-07-02/report__" + FILE_ID + ".pdf",
                "report__" + FILE_ID + ".pdf", "application/pdf", 3);
        MinioStoredObject parsed = new MinioStoredObject(
                "myllm", "parsed/2026-07-02/report__" + FILE_ID + ".parsed.md",
                "report__" + FILE_ID + ".parsed.md", "text/markdown; charset=utf-8", 0);
        when(minioStorageService.storeOriginal(eq(FILE_ID), eq(file), any(LocalDate.class)))
                .thenReturn(original);
        when(minioStorageService.storeParsed(
                eq(FILE_ID), eq("report.pdf"), eq(""), any(LocalDate.class)))
                .thenReturn(parsed);
        when(repository.save(any(DocumentStorageLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DocumentStorageLog saved = service.store(
                "  " + FILE_ID + "  ", "report.pdf", file, parseResult.document(), parseResult);

        verify(minioStorageService).storeOriginal(eq(FILE_ID), eq(file), any(LocalDate.class));
        verify(minioStorageService).storeParsed(
                eq(FILE_ID), eq("report.pdf"), eq(""), any(LocalDate.class));
        assertEquals(FILE_ID, saved.getFileId());
        assertEquals(original.objectPath(), saved.getOriginalObjectPath());
        assertEquals(parsed.objectPath(), saved.getParsedObjectPath());
    }

    @Test
    void rejectsUnsafeFileIdBeforeAnyStorageCall() {
        MockMultipartFile file = sampleFile();
        DocumentParseResult parseResult = sampleParseResult();
        ParsedDocument document = parseResult.document();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.store("../shared", "report.pdf", file, document, parseResult));

        verifyNoInteractions(minioStorageService, repository);
    }

    private static MockMultipartFile sampleFile() {
        return new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[] {1, 2, 3});
    }

    private static DocumentParseResult sampleParseResult() {
        ParsedDocument document = new ParsedDocument("report.pdf", List.of(), Map.of());
        return new DocumentParseResult(
                document, "auto", "local", "local", 1, 5L, List.of("local"), null);
    }
}
