package com.example.myllm.support.minio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.Month;
import org.junit.jupiter.api.Test;

class MinioObjectPathsTests {

    @Test
    void buildsDateBasedOriginalAndParsedPaths() {
        LocalDate date = LocalDate.of(2026, Month.JULY, 2);
        String original = MinioObjectPaths.originalObjectKey("uploads", date, "docs/report.pdf");
        String parsed = MinioObjectPaths.parsedObjectKey(
                "parsed", date, MinioObjectPaths.parsedFileName("docs/report.pdf"));

        assertEquals("uploads/2026-07-02/report.pdf", original);
        assertEquals("parsed/2026-07-02/report.parsed.md", parsed);
    }

    @Test
    void parsedFileNameAppendsSuffixForNonMarkdownFiles() {
        assertEquals("manual.parsed.md", MinioObjectPaths.parsedFileName("manual.docx"));
        assertEquals("notes.parsed.md", MinioObjectPaths.parsedFileName("notes.md"));
    }

    @Test
    void sanitizeFileNameKeepsUnicodeCharacters() {
        String sanitized = MinioObjectPaths.sanitizeFileName("汽车用户手册（2025年版）.docx");
        assertTrue(sanitized.contains("汽车用户手册"));
        assertTrue(sanitized.endsWith(".docx"));
    }
}
