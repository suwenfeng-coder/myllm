package com.example.myllm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.myllm.config.DocForgeProperties;
import com.example.myllm.support.document.DocumentBlock;
import com.example.myllm.support.document.DocumentBlockType;
import com.example.myllm.support.document.DocumentParseResult;
import com.example.myllm.support.document.ParsedDocument;
import com.example.myllm.support.docforge.DocForgeServiceException;
import com.example.myllm.support.parser.DocForgeRemoteDocumentParser;
import com.example.myllm.support.parser.LocalDocumentParser;
import com.example.myllm.testing.LogCapture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class DocumentParseServiceTests {

    private static DocForgeProperties enabledProperties() {
        return new DocForgeProperties(
                true,
                "http://localhost:8000",
                "",
                "markdown",
                5,
                50,
                5000,
                300000,
                2000,
                600000L,
                false);
    }

    private static DocumentParseService createService(
            LocalDocumentParser localParser,
            List<com.example.myllm.support.document.DocumentParser> remoteParsers,
            int autoPreferDoclingAboveMb) {
        return new DocumentParseService(
                localParser,
                remoteParsers,
                Optional.empty(),
                enabledProperties(),
                autoPreferDoclingAboveMb);
    }

    private static DocumentParseService createService(
            LocalDocumentParser localParser,
            List<com.example.myllm.support.document.DocumentParser> remoteParsers) {
        return createService(localParser, remoteParsers, 3);
    }

    private static void assertSingleLine(String message) {
        assertFalse(message.contains("\r"), message);
        assertFalse(message.contains("\n"), message);
    }

    @Test
    void parseUsesLocalParserByDefault() {
        DocumentParseService service = createService(new LocalDocumentParser(), List.of());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.md",
                "text/markdown",
                "# Title\n\nHello docforge plan.".getBytes());

        var result = service.parse(file, "local");

        assertEquals("local", result.parseMode());
        assertEquals("sample.md", result.document().fileName());
        assertEquals("md", result.document().metadata().get("extension"));
    }

    @Test
    void parseRejectsUnknownMode() {
        DocumentParseService service = createService(new LocalDocumentParser(), List.of());

        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());

        assertThrows(IllegalArgumentException.class, () -> service.parse(file, "unknown"));
    }

    @Test
    void parseUsesDoclingParserWhenRequested() {
        DocForgeRemoteDocumentParser doclingParser = new DocForgeRemoteDocumentParser(null, "docling", "docling") {
            @Override
            public boolean supports(String extension) {
                return "pdf".equals(extension);
            }

            @Override
            public ParsedDocument parse(org.springframework.web.multipart.MultipartFile file) {
                return new ParsedDocument(
                        "report.pdf",
                        List.of(new DocumentBlock(DocumentBlockType.PARAGRAPH, "pdf text", null, 0)),
                        Map.of("extension", "pdf", "parser", "docling", "parsePages", 3));
            }
        };

        DocumentParseService service = createService(new LocalDocumentParser(), List.of(doclingParser));

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF-1.4".getBytes());

        var result = service.parse(file, "docling");

        assertEquals("docling", result.parseMode());
        assertEquals("docling", result.parserEngine());
        assertEquals(3, result.parsedPages());
        assertEquals("report.pdf", result.document().fileName());
    }

    @Test
    void parseNormalizesMarkerAliasToMaker() {
        DocForgeRemoteDocumentParser makerParser = new DocForgeRemoteDocumentParser(null, "maker", "maker") {
            @Override
            public boolean supports(String extension) {
                return "pdf".equals(extension);
            }

            @Override
            public ParsedDocument parse(org.springframework.web.multipart.MultipartFile file) {
                return new ParsedDocument(
                        "report.pdf",
                        List.of(new DocumentBlock(DocumentBlockType.PARAGRAPH, "marker text", null, 0)),
                        Map.of("extension", "pdf", "parser", "maker", "parsePages", 2));
            }
        };

        DocumentParseService service = createService(new LocalDocumentParser(), List.of(makerParser));

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF-1.4".getBytes());

        var result = service.parse(file, "maker");

        assertEquals("maker", result.parseMode());
        assertEquals("maker", result.parserEngine());
        assertEquals(2, result.parsedPages());
    }

    @Test
    void autoFallsBackFromMakerToDoclingForPdf() {
        DocForgeRemoteDocumentParser makerParser = new DocForgeRemoteDocumentParser(null, "maker", "maker") {
            @Override
            public boolean supports(String extension) {
                return "pdf".equals(extension);
            }

            @Override
            public ParsedDocument parse(org.springframework.web.multipart.MultipartFile file) {
                throw new DocForgeServiceException("maker model unavailable", 503);
            }
        };
        DocForgeRemoteDocumentParser doclingParser = new DocForgeRemoteDocumentParser(null, "docling", "docling") {
            @Override
            public boolean supports(String extension) {
                return "pdf".equals(extension);
            }

            @Override
            public ParsedDocument parse(org.springframework.web.multipart.MultipartFile file) {
                return new ParsedDocument(
                        "report.pdf",
                        List.of(new DocumentBlock(DocumentBlockType.PARAGRAPH, "docling fallback", null, 0)),
                        Map.of("extension", "pdf", "parser", "docling", "parsePages", 4));
            }
        };
        DocumentParseService service = createService(new LocalDocumentParser(), List.of(makerParser, doclingParser));
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF-1.4".getBytes());

        var result = service.parse(file, "auto");

        assertEquals("auto", result.requestedParseMode());
        assertEquals("docling", result.parseMode());
        assertEquals(List.of("maker", "docling"), result.attemptedModes());
        org.junit.jupiter.api.Assertions.assertTrue(result.fallbackReason().contains("maker model unavailable"));
    }

    @Test
    void autoUsesLocalFirstForLocallySupportedFormat() {
        DocumentParseService service = createService(new LocalDocumentParser(), List.of());
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.md", "text/markdown", "# title\n\ncontent".getBytes());

        var result = service.parse(file, null);

        assertEquals("auto", result.requestedParseMode());
        assertEquals("local", result.parseMode());
        assertEquals(List.of("local"), result.attemptedModes());
    }

    @Test
    void autoPrefersDoclingForLargePdf() {
        DocForgeRemoteDocumentParser makerParser = new DocForgeRemoteDocumentParser(null, "maker", "maker") {
            @Override
            public boolean supports(String extension) {
                return "pdf".equals(extension);
            }

            @Override
            public ParsedDocument parse(org.springframework.web.multipart.MultipartFile file) {
                throw new IllegalStateException("maker should not run for large pdf");
            }
        };
        DocForgeRemoteDocumentParser doclingParser = new DocForgeRemoteDocumentParser(null, "docling", "docling") {
            @Override
            public boolean supports(String extension) {
                return "pdf".equals(extension);
            }

            @Override
            public ParsedDocument parse(org.springframework.web.multipart.MultipartFile file) {
                return new ParsedDocument(
                        "large.pdf",
                        List.of(new DocumentBlock(DocumentBlockType.PARAGRAPH, "docling large", null, 0)),
                        Map.of("extension", "pdf", "parser", "docling"));
            }
        };
        DocumentParseService service = createService(
                new LocalDocumentParser(), List.of(makerParser, doclingParser), 3);
        byte[] payload = new byte[4 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", payload);

        var result = service.parse(file, "auto");

        assertEquals("docling", result.parseMode());
        assertEquals(List.of("docling"), result.attemptedModes());
    }

    @Test
    void autoParseOmitsExtensionFromLogsWithoutChangingBusinessValue() {
        String rawExtension = "safe\r\nforged";
        AtomicReference<String> receivedExtension = new AtomicReference<>();
        LocalDocumentParser localParser = new LocalDocumentParser() {
            @Override
            public boolean supports(String extension) {
                receivedExtension.set(extension);
                return true;
            }

            @Override
            public ParsedDocument parse(MultipartFile file) {
                return new ParsedDocument(
                        file.getOriginalFilename(),
                        List.of(new DocumentBlock(
                                DocumentBlockType.PARAGRAPH, "content", null, 0)),
                        Map.of("extension", receivedExtension.get()));
            }
        };
        DocumentParseService service = createService(localParser, List.of());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report." + rawExtension,
                "application/octet-stream",
                "content".getBytes());

        try (LogCapture logs = LogCapture.forClass(DocumentParseService.class)) {
            DocumentParseResult result = service.parse(file, "auto");

            assertEquals(rawExtension, receivedExtension.get());
            assertEquals(rawExtension, result.document().metadata().get("extension"));
            String success = logs.eventStartingWith("自动解析完成").getFormattedMessage();
            assertEquals(
                    "自动解析完成 requested=auto applied=local attempted=[local]",
                    success);
            assertSingleLine(success);
        }
    }

    @Test
    void autoParseOmitsFailureDetailsFromLogsWithoutChangingFallbackReason() {
        DocForgeRemoteDocumentParser makerParser =
                new DocForgeRemoteDocumentParser(null, "maker", "maker") {
                    @Override
                    public boolean supports(String extension) {
                        return "pdf".equals(extension);
                    }

                    @Override
                    public ParsedDocument parse(MultipartFile file) {
                        throw new DocForgeServiceException("maker model\r\nunavailable", 503);
                    }
                };
        DocForgeRemoteDocumentParser doclingParser =
                new DocForgeRemoteDocumentParser(null, "docling", "docling") {
                    @Override
                    public boolean supports(String extension) {
                        return "pdf".equals(extension);
                    }

                    @Override
                    public ParsedDocument parse(MultipartFile file) {
                        return new ParsedDocument(
                                "report.pdf",
                                List.of(new DocumentBlock(
                                        DocumentBlockType.PARAGRAPH,
                                        "docling fallback",
                                        null,
                                        0)),
                                Map.of(
                                        "extension", "pdf",
                                        "parser", "docling",
                                        "parsePages", 4));
                    }
                };
        DocumentParseService service =
                createService(new LocalDocumentParser(), List.of(makerParser, doclingParser));
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF-1.4".getBytes());

        try (LogCapture logs = LogCapture.forClass(DocumentParseService.class)) {
            DocumentParseResult result = service.parse(file, "auto");

            String rawFallbackReason = "maker: maker model\r\nunavailable";
            assertEquals(rawFallbackReason, result.fallbackReason());
            assertEquals(rawFallbackReason, result.document().metadata().get("parseFallbackReason"));

            String warning = logs.eventStartingWith("自动解析候选失败").getFormattedMessage();
            assertEquals(
                    "自动解析候选失败，将尝试下一模式 mode=maker",
                    warning);
            assertSingleLine(warning);

            String success = logs.eventStartingWith("自动解析完成").getFormattedMessage();
            assertEquals(
                    "自动解析完成 requested=auto applied=docling attempted=[maker, docling]",
                    success);
            assertSingleLine(success);
        }
    }
}
