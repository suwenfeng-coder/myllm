package com.example.myllm.service;

import com.example.myllm.config.DocForgeProperties;
import com.example.myllm.dto.ParseCapabilitiesResponse;
import com.example.myllm.dto.ParseCapabilitiesResponse.RemoteEngineCapability;
import com.example.myllm.support.docforge.DocForgeClient;
import com.example.myllm.support.docforge.DocForgeEngineInfo;
import com.example.myllm.support.docforge.DocForgeFormatsResponse;
import com.example.myllm.support.document.DocumentParseResult;
import com.example.myllm.support.document.ParseProgressListener;
import com.example.myllm.support.document.DocumentParser;
import com.example.myllm.support.document.ParsedDocument;
import com.example.myllm.support.parser.LocalDocumentParser;
import com.example.myllm.support.FileTextExtractor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);
    private static final String MODE_LOCAL = "local";
    private static final String MODE_DOCLING = "docling";
    private static final String MODE_MAKER = "maker";
    private static final Set<String> REMOTE_PARSE_MODES = Set.of(MODE_DOCLING, MODE_MAKER);

    private final LocalDocumentParser localDocumentParser;
    private final Map<String, DocumentParser> parsersByMode;
    private final Optional<DocForgeClient> docForgeClient;
    private final DocForgeProperties docForgeProperties;
    private final int autoPreferDoclingAboveMb;

    public DocumentParseService(
            LocalDocumentParser localDocumentParser,
            List<DocumentParser> documentParsers,
            Optional<DocForgeClient> docForgeClient,
            DocForgeProperties docForgeProperties,
            @Value("${document.parsing.auto-prefer-docling-above-mb:3}") int autoPreferDoclingAboveMb) {
        this.localDocumentParser = localDocumentParser;
        this.parsersByMode = documentParsers.stream()
                .collect(Collectors.toMap(
                        DocumentParser::mode,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        this.docForgeClient = docForgeClient;
        this.docForgeProperties = docForgeProperties;
        this.autoPreferDoclingAboveMb = Math.max(1, autoPreferDoclingAboveMb);
    }

    public DocumentParseResult parse(MultipartFile file, String parseMode) {
        return parse(file, parseMode, ParseProgressListener.disabled());
    }

    public DocumentParseResult parse(
            MultipartFile file, String parseMode, ParseProgressListener listener) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String requestedMode = normalizeParseMode(parseMode);
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String extension = FileTextExtractor.extensionOf(fileName);
        if (!"auto".equals(requestedMode)) {
            return parseExplicit(file, requestedMode, extension, listener);
        }
        return parseAutomatically(file, extension, listener);
    }

    @SuppressWarnings("java:S3776") // Capability negotiation intentionally handles local and remote fallbacks together.
    public ParseCapabilitiesResponse getCapabilities() {
        List<String> localExtensions = LocalDocumentParser.SUPPORTED_EXTENSIONS.stream()
                .sorted()
                .map(ext -> "." + ext)
                .toList();

        boolean docforgeEnabled = docForgeProperties.enabled();
        boolean docforgeReady = docforgeEnabled && docForgeClient.map(DocForgeClient::isReady).orElse(false);
        int syncMax = docForgeProperties.syncMaxSizeMb();
        int asyncMax = docForgeProperties.asyncMaxSizeMb();
        List<RemoteEngineCapability> engines = List.of();
        Set<String> remoteExtensionSet = new LinkedHashSet<>();

        if (docforgeEnabled && docForgeClient.isPresent()) {
            DocForgeClient client = docForgeClient.get();
            List<DocForgeEngineInfo> engineInfos = client.listEngines().engines();
            if (engineInfos.isEmpty()) {
                DocForgeFormatsResponse formats = client.getFormats();
                syncMax = formats.syncMaxFileSizeMb();
                asyncMax = formats.maxFileSizeMb();
                List<String> extensions = normalizeExtensions(formats.inputExtensions());
                if (docforgeReady) {
                    extensions.forEach(remoteExtensionSet::add);
                }
                engines = List.of(new RemoteEngineCapability(
                        DocForgeClient.toParseMode(formats.engine()),
                        formats.engine() == null ? MODE_DOCLING : formats.engine(),
                        true,
                        docforgeReady,
                        docforgeReady ? null : "DocForge 服务未就绪",
                        extensions,
                        formats.outputFormats() == null ? List.of() : formats.outputFormats()));
            } else {
                List<RemoteEngineCapability> built = new ArrayList<>();
                for (DocForgeEngineInfo info : engineInfos) {
                    List<String> extensions = normalizeExtensions(info.inputExtensions());
                    boolean available = docforgeReady && info.available();
                    if (available) {
                        extensions.forEach(remoteExtensionSet::add);
                    }
                    built.add(new RemoteEngineCapability(
                            DocForgeClient.toParseMode(info.name()),
                            info.name(),
                            info.defaultEngine(),
                            available,
                            info.unavailableReason(),
                            extensions,
                            info.outputFormats() == null ? List.of() : info.outputFormats()));
                }
                engines = List.copyOf(built);
                DocForgeFormatsResponse formats = client.getFormats();
                syncMax = formats.syncMaxFileSizeMb();
                asyncMax = formats.maxFileSizeMb();
            }
        }

        return new ParseCapabilitiesResponse(
                localExtensions,
                docforgeEnabled,
                docforgeReady,
                engines,
                remoteExtensionSet.stream().sorted().toList(),
                syncMax,
                asyncMax);
    }

    private DocumentParser resolveParser(String parseMode) {
        if (MODE_LOCAL.equals(parseMode)) {
            return localDocumentParser;
        }
        if (!docForgeProperties.enabled()) {
            throw new IllegalArgumentException("远程解析未启用，请在配置中设置 docforge.enabled=true");
        }
        DocumentParser parser = parsersByMode.get(parseMode);
        if (parser == null) {
            throw new IllegalStateException("远程解析器未初始化: " + parseMode + "，请检查 DocForge 服务配置");
        }
        return parser;
    }

    private DocumentParseResult parseExplicit(
            MultipartFile file,
            String requestedMode,
            String extension,
            ParseProgressListener listener) {
        DocumentParser parser = resolveParser(requestedMode);
        if (!parser.supports(extension)) {
            throw new IllegalArgumentException(
                    requestedMode + " 解析器不支持该文件类型: ." + extension);
        }
        long start = System.currentTimeMillis();
        ParsedDocument document = listener == ParseProgressListener.NOOP
                ? parser.parse(file)
                : parser.parse(file, listener);
        long durationMs = System.currentTimeMillis() - start;
        ParsedDocument enriched = enrichParseMetadata(
                document, requestedMode, parser.mode(), List.of(parser.mode()), null);
        return DocumentParseResult.from(
                enriched, requestedMode, parser.mode(), durationMs, List.of(parser.mode()), null);
    }

    private DocumentParseResult parseAutomatically(
            MultipartFile file, String extension, ParseProgressListener listener) {
        List<String> candidates = autoCandidates(extension, file.getSize());
        List<String> attempted = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        RuntimeException lastFailure = null;
        long start = System.currentTimeMillis();

        for (String mode : candidates) {
            DocumentParser parser = availableParser(mode);
            if (parser == null || !safeSupports(parser, extension, failures)) {
                continue;
            }
            attempted.add(mode);
            try {
                String appliedMode = parser.mode();
                ParsedDocument document = listener == ParseProgressListener.NOOP
                        ? parser.parse(file)
                        : parser.parse(file, listener);
                long durationMs = System.currentTimeMillis() - start;
                String fallbackReason = failures.isEmpty() ? null : String.join("; ", failures);
                ParsedDocument enriched = enrichParseMetadata(
                        document, "auto", appliedMode, attempted, fallbackReason);
                log.info("自动解析完成 requested=auto applied={} extension={} attempted={} fallbackReason={}",
                        appliedMode, extension, attempted, fallbackReason);
                return DocumentParseResult.from(
                        enriched, "auto", appliedMode, durationMs, attempted, fallbackReason);
            } catch (RuntimeException e) {
                lastFailure = e;
                failures.add(mode + ": " + conciseMessage(e));
                if (log.isWarnEnabled()) {
                    log.warn("自动解析候选失败，将尝试下一模式 mode={} extension={} reason={}",
                            mode, extension, conciseMessage(e));
                }
            }
        }

        String message = "自动解析失败，文件类型=." + extension
                + "，尝试链=" + attempted
                + (failures.isEmpty() ? "" : "，原因=" + failures);
        throw new IllegalStateException(message, lastFailure);
    }

    private DocumentParser availableParser(String mode) {
        if (MODE_LOCAL.equals(mode)) {
            return localDocumentParser;
        }
        if (!docForgeProperties.enabled()) {
            return null;
        }
        return parsersByMode.get(mode);
    }

    private static boolean safeSupports(
            DocumentParser parser,
            String extension,
            List<String> failures) {
        try {
            boolean supported = parser.supports(extension);
            if (!supported) {
                failures.add(parser.mode() + ": 不支持 ." + extension);
            }
            return supported;
        } catch (RuntimeException e) {
            failures.add(parser.mode() + " 能力探测失败: " + conciseMessage(e));
            return false;
        }
    }

    private List<String> autoCandidates(String extension, long fileSizeBytes) {
        if ("pdf".equals(extension)) {
            long thresholdBytes = (long) autoPreferDoclingAboveMb * 1024L * 1024L;
            if (fileSizeBytes > thresholdBytes) {
                return List.of(MODE_DOCLING, MODE_MAKER);
            }
            return List.of(MODE_MAKER, MODE_DOCLING);
        }
        if (localDocumentParser.supports(extension)) {
            return List.of(MODE_LOCAL, MODE_DOCLING);
        }
        return List.of(MODE_DOCLING);
    }

    private static ParsedDocument enrichParseMetadata(
            ParsedDocument document,
            String requestedMode,
            String appliedMode,
            List<String> attemptedModes,
            String fallbackReason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (document.metadata() != null) {
            metadata.putAll(document.metadata());
        }
        metadata.put("requestedParseMode", requestedMode);
        metadata.put("appliedParseMode", appliedMode);
        metadata.put("attemptedParseModes", List.copyOf(attemptedModes));
        if (fallbackReason != null && !fallbackReason.isBlank()) {
            metadata.put("parseFallbackReason", fallbackReason);
        }
        return new ParsedDocument(document.fileName(), document.blocks(), Map.copyOf(metadata));
    }

    private static String conciseMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() <= 200 ? message : message.substring(0, 200) + "...";
    }

    private static String normalizeParseMode(String parseMode) {
        if (parseMode == null || parseMode.isBlank()) {
            return "auto";
        }
        String normalized = parseMode.trim().toLowerCase(Locale.ROOT);
        if (MODE_LOCAL.equals(normalized) || "auto".equals(normalized)) {
            return normalized;
        }
        if ("marker".equals(normalized)) {
            return MODE_MAKER;
        }
        if (REMOTE_PARSE_MODES.contains(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException(
                "不支持的 parseMode: " + parseMode + "，可选值: auto, local, docling, maker");
    }

    private static List<String> normalizeExtensions(List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return List.of();
        }
        return extensions.stream()
                .map(ext -> ext.startsWith(".") ? ext : "." + ext)
                .distinct()
                .sorted()
                .toList();
    }
}
