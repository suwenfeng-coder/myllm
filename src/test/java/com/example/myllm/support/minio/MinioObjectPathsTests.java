package com.example.myllm.support.minio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.Month;
import org.junit.jupiter.api.Test;

class MinioObjectPathsTests {

    private static final LocalDate STORAGE_DATE = LocalDate.of(2026, Month.JULY, 2);
    private static final String FILE_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void buildsReadableUniqueOriginalAndParsedPaths() {
        String original = MinioObjectPaths.originalObjectKey(
                "uploads", STORAGE_DATE, FILE_ID, "docs/report.pdf");
        String parsed = MinioObjectPaths.parsedObjectKey(
                "parsed", STORAGE_DATE, FILE_ID, "docs/report.pdf");

        assertEquals(
                "uploads/2026-07-02/report__550e8400-e29b-41d4-a716-446655440000.pdf",
                original);
        assertEquals(
                "parsed/2026-07-02/report__550e8400-e29b-41d4-a716-446655440000.parsed.md",
                parsed);
    }

    @Test
    void sameDayAndFilenameRemainUniqueAcrossFileIds() {
        String first = MinioObjectPaths.originalObjectKey(
                "uploads", STORAGE_DATE, "11111111-1111-1111-1111-111111111111", "report.pdf");
        String second = MinioObjectPaths.originalObjectKey(
                "uploads", STORAGE_DATE, "22222222-2222-2222-2222-222222222222", "report.pdf");

        assertNotEquals(first, second);
    }

    @Test
    void preservesLastExtensionAndHandlesMissingExtension() {
        assertEquals(
                "archive.tar__550e8400-e29b-41d4-a716-446655440000.gz",
                MinioObjectPaths.originalFileName(FILE_ID, "archive.tar.gz"));
        assertEquals(
                "README__550e8400-e29b-41d4-a716-446655440000",
                MinioObjectPaths.originalFileName(FILE_ID, "README"));
        assertEquals(
                "unknown__550e8400-e29b-41d4-a716-446655440000",
                MinioObjectPaths.originalFileName(FILE_ID, " "));
    }

    @Test
    void rejectsBlankOrUnsafeFileIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MinioObjectPaths.originalFileName(" ", "report.pdf"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MinioObjectPaths.originalFileName("../shared", "report.pdf"));
    }

    @Test
    void sanitizeFileNameKeepsUnicodeCharacters() {
        String sanitized = MinioObjectPaths.sanitizeFileName("汽车用户手册（2025年版）.docx");
        assertTrue(sanitized.contains("汽车用户手册"));
        assertTrue(sanitized.endsWith(".docx"));
    }
}
