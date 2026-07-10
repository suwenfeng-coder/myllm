package com.example.myllm.support.minio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myllm.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Month;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class MinioStorageServiceTests {

    private static final String FILE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final LocalDate STORAGE_DATE = LocalDate.of(2026, Month.JULY, 2);

    private MinioClient minioClient;
    private MinioStorageService service;

    @BeforeEach
    void setUp() throws Exception {
        minioClient = mock(MinioClient.class);
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        MinioProperties properties = new MinioProperties(
                true,
                "http://127.0.0.1:9000",
                "access",
                "secret",
                "myllm",
                "uploads",
                "parsed",
                false);
        service = new MinioStorageService(minioClient, properties);
    }

    @Test
    void originalUploadUsesAndReturnsUniqueReadableName() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "docs/report.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8));

        MinioStoredObject stored = service.storeOriginal(FILE_ID, file, STORAGE_DATE);

        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(args.capture());
        assertEquals(
                "uploads/2026-07-02/report__550e8400-e29b-41d4-a716-446655440000.pdf",
                args.getValue().object());
        assertEquals("report__550e8400-e29b-41d4-a716-446655440000.pdf", stored.fileName());
    }

    @Test
    void parsedUploadUsesMatchingUniqueReadableName() throws Exception {
        MinioStoredObject stored = service.storeParsed(FILE_ID, "docs/report.pdf", "body", STORAGE_DATE);

        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(args.capture());
        assertEquals(
                "parsed/2026-07-02/report__550e8400-e29b-41d4-a716-446655440000.parsed.md",
                args.getValue().object());
        assertEquals(
                "report__550e8400-e29b-41d4-a716-446655440000.parsed.md",
                stored.fileName());
    }
}
