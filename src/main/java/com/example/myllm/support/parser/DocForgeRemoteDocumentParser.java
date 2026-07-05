package com.example.myllm.support.parser;

import com.example.myllm.support.FileTextExtractor;
import com.example.myllm.support.docforge.DocForgeClient;
import com.example.myllm.support.docforge.DocForgeParseResponse;
import com.example.myllm.support.docforge.DocForgeServiceException;
import com.example.myllm.support.document.DocumentBlock;
import com.example.myllm.support.document.DocumentParser;
import com.example.myllm.support.document.ParsedDocument;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.myllm.support.document.ParseProgressListener;
import org.springframework.web.multipart.MultipartFile;

public class DocForgeRemoteDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocForgeRemoteDocumentParser.class);

    private final DocForgeClient docForgeClient;
    private final String parseMode;
    private final String docForgeEngine;

    public DocForgeRemoteDocumentParser(DocForgeClient docForgeClient, String parseMode, String docForgeEngine) {
        this.docForgeClient = docForgeClient;
        this.parseMode = parseMode;
        this.docForgeEngine = docForgeEngine;
    }

    @Override
    public String mode() {
        return parseMode;
    }

    @Override
    public boolean supports(String extension) {
        if (extension == null || extension.isBlank()) {
            return false;
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        return docForgeClient.getEngineExtensions(docForgeEngine).stream()
                .anyMatch(ext -> normalized.equals(ext.toLowerCase(Locale.ROOT)));
    }

    @Override
    public ParsedDocument parse(MultipartFile file) {
        return doParse(file, ParseProgressListener.disabled());
    }

    @Override
    public ParsedDocument parse(MultipartFile file, ParseProgressListener listener) {
        return doParse(file, listener);
    }

    private ParsedDocument doParse(MultipartFile file, ParseProgressListener listener) {
        if (!docForgeClient.isEngineReady(docForgeEngine)) {
            throw new DocForgeServiceException(
                    "DocForge 引擎未就绪: " + docForgeEngine + "，请检查 /ready 与 /v1/engines", 503);
        }

        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String extension = FileTextExtractor.extensionOf(fileName);
        if (!supports(extension)) {
            throw new IllegalArgumentException(parseMode + " 解析器不支持该文件类型: ." + extension);
        }

        DocForgeParseResponse response = docForgeClient.parse(file, parseMode, listener);
        String markdown = response.contentAsText();
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException(parseMode + " 未解析出有效文本内容: " + fileName);
        }

        List<DocumentBlock> blocks = FileTextExtractor.parseMarkdown(markdown);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException(parseMode + " 解析结果为空: " + fileName);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("extension", extension);
        metadata.put("parser", parseMode);
        String reportedEngine = response.engine() == null ? docForgeEngine : response.engine();
        String actualEngine = response.metadata() != null && response.metadata().actualEngine() != null
                ? response.metadata().actualEngine()
                : reportedEngine;
        metadata.put("docForgeEngine", reportedEngine);
        metadata.put("actualParserEngine", actualEngine);
        metadata.put("detectedFormat", parseMode.toUpperCase(Locale.ROOT));
        if (response.metadata() != null) {
            metadata.put("parseInputFormat", response.metadata().inputFormat());
            metadata.put("parsePages", response.metadata().pages());
            metadata.put("parseDurationMs", response.metadata().durationMs());
            metadata.put("parseStatus", response.metadata().status());
            if (response.metadata().fallbackReason() != null
                    && !response.metadata().fallbackReason().isBlank()) {
                metadata.put("engineFallbackReason", response.metadata().fallbackReason());
            }
        }
        log.info("{} 解析完成 file={} extension={} blocks={} pages={}",
                parseMode, fileName, extension, blocks.size(),
                response.metadata() == null ? null : response.metadata().pages());
        return new ParsedDocument(fileName, blocks, Map.copyOf(metadata));
    }
}
