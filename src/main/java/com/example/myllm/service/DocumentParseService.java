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

/**
 * 文档解析路由服务，统一本地解析和 DocForge 远程解析的业务入口。
 *
 * <p>支持显式模式 {@code local/docling/maker} 和自动模式 {@code auto}。自动模式会根据文件类型、大小和
 * 引擎能力选择候选链，并记录实际执行模式、尝试链和降级原因，供入库审计、前端展示和问题排查使用。</p>
 *
 * <p>本类只负责“选择谁来解析”和“补齐解析元数据”；具体格式解析能力由 {@link LocalDocumentParser} 和
 * DocForge 适配器提供。</p>
 */
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

    /**
     * 解析上传文件并返回带审计元数据的结构化文档。
     *
     * <p>显式模式失败会直接抛出，避免用户以为指定引擎已成功；自动模式会按候选链逐个尝试，直到一个解析器
     * 成功或所有候选失败。</p>
     */
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

    /**
     * 汇总前端上传页需要的解析能力。
     *
     * <p>这里会合并本地格式白名单、DocForge 全局 formats 和新版 engines 状态。若远程服务未就绪，会返回
     * enabled/ready/unavailableReason，让页面能禁用不适合的解析模式，而不是等上传后才失败。</p>
     */
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

    /**
     * 自动解析策略。
     *
     * <p>PDF 默认 Maker 优先以获得更好的版面/表格效果，但大 PDF 会优先 Docling，避免 Maker 长时间加载或
     * 解析阻塞；本地可稳定解析的格式优先 local，远程 Docling 作为兜底。</p>
     */
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

                log.info("自动解析完成 requested=auto applied={} attempted={}",
                        appliedMode, attempted);

                return DocumentParseResult.from(
                        enriched, "auto", appliedMode, durationMs, attempted, fallbackReason);
            } catch (RuntimeException e) {
                lastFailure = e;
                failures.add(mode + ": " + conciseMessage(e));
                if (log.isWarnEnabled()) {
                    log.warn("自动解析候选失败，将尝试下一模式 mode={}", mode);
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
            long thresholdBytes = autoPreferDoclingAboveMb * 1024L * 1024L;
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
