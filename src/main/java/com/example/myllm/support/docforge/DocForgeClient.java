package com.example.myllm.support.docforge;

import com.example.myllm.config.DocForgeProperties;
import com.example.myllm.support.FileTextExtractor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.example.myllm.support.document.ParseProgressListener;
import org.springframework.web.multipart.MultipartFile;

/**
 * DocForge 远程解析服务客户端。
 *
 * <p>负责与 Python 侧 DocForge 的能力接口、同步解析接口和异步任务接口通信。Java 业务层只关心
 * {@code docling/maker} 等解析模式，本类负责把模式映射为远程 engine，并把 HTTP/传输错误翻译成用户可读的
 * {@link DocForgeServiceException}。</p>
 *
 * <p>能力结果会短暂缓存，避免上传页频繁刷新时反复探测 Python 服务。解析正文仍以服务端实际返回为准。</p>
 */
@Component
@ConditionalOnProperty(prefix = "docforge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocForgeClient {

    private static final Logger log = LoggerFactory.getLogger(DocForgeClient.class);

    private static final List<String> FALLBACK_EXTENSIONS = List.of(
            "pdf", "docx", "docm", "dotx", "dotm", "pptx", "pptm", "potx", "potm", "ppsx", "ppsm",
            "xlsx", "xlsm", "csv", "md", "html", "htm", "xhtml", "png", "jpg", "jpeg", "bmp", "tif", "tiff", "webp");
    private static final long CAPABILITY_CACHE_TTL_MS = 30_000L;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;
    private static final String ENGINE_MAKER = "maker";
    private static final String ENGINE_DOCLING = "docling";
    private static final String UNKNOWN = "unknown";

    private final RestClient restClient;
    private final DocForgeProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicReference<DocForgeFormatsResponse> cachedFormats = new AtomicReference<>();
    private final AtomicReference<DocForgeEnginesResponse> cachedEngines = new AtomicReference<>();
    private volatile long formatsCachedAt;
    private volatile long enginesCachedAt;

    public DocForgeClient(RestClient docforgeRestClient, DocForgeProperties properties, ObjectMapper objectMapper) {
        this.restClient = docforgeRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public static String toDocForgeEngine(String parseMode) {
        return switch (parseMode) {
            case "marker", ENGINE_MAKER -> ENGINE_MAKER;
            case ENGINE_DOCLING -> ENGINE_DOCLING;
            default -> throw new IllegalArgumentException("不支持的远程解析模式: " + parseMode);
        };
    }

    public static String toParseMode(String docForgeEngine) {
        if (docForgeEngine == null || docForgeEngine.isBlank()) {
            return ENGINE_DOCLING;
        }
        return switch (docForgeEngine.toLowerCase(Locale.ROOT)) {
            case ENGINE_MAKER, "marker" -> ENGINE_MAKER;
            case ENGINE_DOCLING -> ENGINE_DOCLING;
            default -> docForgeEngine.toLowerCase(Locale.ROOT);
        };
    }

    public boolean isReady() {
        try {
            ReadyResponse response = restClient.get()
                    .uri("/ready")
                    .retrieve()
                    .body(ReadyResponse.class);
            return response != null && response.engineInitialized();
        } catch (Exception e) {
            log.debug("DocForge /ready 探测失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 查询 DocForge 当前可用引擎。
     *
     * <p>新版 DocForge 会返回每个引擎的 enabled/installed/ready 状态；旧版或异常时返回空列表，由上层按
     * 单引擎 Docling 兼容逻辑处理。</p>
     */
    public DocForgeEnginesResponse listEngines() {
        DocForgeEnginesResponse cached = cachedEngines.get();
        if (cached != null && !cacheExpired(enginesCachedAt)) {
            return cached;
        }
        try {
            DocForgeEnginesResponse response = restClient.get()
                    .uri("/v1/engines")
                    .retrieve()
                    .body(DocForgeEnginesResponse.class);
            if (response != null && response.engines() != null && !response.engines().isEmpty()) {
                cachedEngines.set(response);
                enginesCachedAt = System.currentTimeMillis();
                return response;
            }
        } catch (Exception e) {
            log.warn("获取 DocForge 引擎列表失败: {}", e.getMessage());
        }
        return new DocForgeEnginesResponse(List.of());
    }

    public DocForgeFormatsResponse getFormats() {
        DocForgeFormatsResponse cached = cachedFormats.get();
        if (cached != null && !cacheExpired(formatsCachedAt)) {
            return cached;
        }
        try {
            DocForgeFormatsResponse response = restClient.get()
                    .uri("/v1/formats")
                    .retrieve()
                    .body(DocForgeFormatsResponse.class);
            if (response != null && response.inputExtensions() != null && !response.inputExtensions().isEmpty()) {
                cachedFormats.set(response);
                formatsCachedAt = System.currentTimeMillis();
                return response;
            }
        } catch (Exception e) {
            log.warn("获取 DocForge 格式列表失败，使用内置回退列表: {}", e.getMessage());
        }
        return fallbackFormats(null);
    }

    public List<String> getEngineExtensions(String engine) {
        String normalizedEngine = toDocForgeEngine(engine);
        List<DocForgeEngineInfo> engines = listEngines().engines();
        List<String> extensions = engines.stream()
                .filter(info -> normalizedEngine.equalsIgnoreCase(info.name()))
                .findFirst()
                .map(DocForgeEngineInfo::inputExtensions)
                .orElse(List.of());
        if (!extensions.isEmpty()) {
            return extensions;
        }
        // 旧版单引擎 DocForge 没有 /v1/engines 时，仅允许默认 Docling 使用 formats 回退。
        return engines.isEmpty() && ENGINE_DOCLING.equals(normalizedEngine)
                ? getFormats().inputExtensions()
                : List.of();
    }

    /** 判断指定引擎是否由服务端公布且处于可用状态。 */
    public boolean isEngineReady(String engine) {
        if (!isReady()) {
            return false;
        }
        String normalizedEngine = toDocForgeEngine(engine);
        List<DocForgeEngineInfo> engines = listEngines().engines();
        if (engines.isEmpty()) {
            return ENGINE_DOCLING.equals(normalizedEngine);
        }
        return engines.stream()
                .filter(info -> normalizedEngine.equalsIgnoreCase(info.name()))
                .anyMatch(DocForgeEngineInfo::available);
    }

    public DocForgeParseResponse parse(MultipartFile file, String engine) {
        return parse(file, engine, null);
    }

    /**
     * 解析上传文件。
     *
     * <p>小文件优先同步解析；Maker、PDF Docling 或超过同步大小阈值的文件会转为异步任务并轮询进度。
     * listener 用于把 Python 侧 job 进度桥接到 Java 上传任务状态。</p>
     */
    public DocForgeParseResponse parse(MultipartFile file, String engine, ParseProgressListener listener) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String docForgeEngine = toDocForgeEngine(engine);
        if (!isEngineReady(docForgeEngine)) {
            throw new DocForgeServiceException(
                    "DocForge 引擎不可用: " + docForgeEngine + "，请检查 /v1/engines 状态", 503);
        }
        long fileSize = file.getSize();
        long syncLimitBytes = properties.syncMaxSizeMb() * 1024L * 1024L;
        long asyncLimitBytes = properties.asyncMaxSizeMb() * 1024L * 1024L;
        if (fileSize > asyncLimitBytes) {
            throw new IllegalArgumentException(
                    "文件超过 DocForge 最大限制 " + properties.asyncMaxSizeMb() + "MB，请缩小文件后重试");
        }
        String fileName = originalFileName(file);
        String extension = FileTextExtractor.extensionOf(fileName);
        if (shouldUseAsync(docForgeEngine, fileSize, syncLimitBytes, extension)) {
            return parseAsync(file, docForgeEngine, listener);
        }
        try {
            return parseSync(file, docForgeEngine);
        } catch (DocForgeServiceException e) {
            if (e.statusCode() >= 500) {
                log.warn(
                        "DocForge 同步解析失败(status={})，改走异步: engine={}",
                        e.statusCode(),
                        docForgeEngine);
                return parseAsync(file, docForgeEngine, listener);
            }
            throw e;
        }
    }

    private boolean shouldUseAsync(String docForgeEngine, long fileSize, long syncLimitBytes, String extension) {
        // Maker/Marker 首次加载模型 + 大 PDF 常超过同步 HTTP 读超时，统一走异步任务。
        if (ENGINE_MAKER.equals(docForgeEngine)) {
            return true;
        }
        // Docling 解析 PDF 会懒加载布局模型，同步请求易超时或与其他任务争抢资源。
        if (ENGINE_DOCLING.equals(docForgeEngine) && "pdf".equals(extension)) {
            return true;
        }
        return fileSize > syncLimitBytes;
    }

    private DocForgeParseResponse parseSync(MultipartFile file, String engine) {
        String fileName = originalFileName(file);
        log.info("DocForge 同步解析: engine={}", engine);
        try {
            return restClient.post()
                    .uri("/v1/parse/sync")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(buildMultipartBody(file, engine))
                    .retrieve()
                    .body(DocForgeParseResponse.class);
        } catch (RestClientResponseException e) {
            throw mapResponseException(e, "DocForge 同步解析失败");
        } catch (ResourceAccessException e) {
            throw mapTransportException(e, "DocForge 同步解析失败");
        } catch (IOException e) {
            throw new DocForgeServiceException("读取上传文件失败: " + fileName, e);
        }
    }

    /**
     * 创建 DocForge 异步任务并轮询直到终态。
     *
     * <p>这里把 {@code succeeded/failed/cancelled} 三类终态显式处理；超出全局等待时间后抛出超时异常，
     * 让上传任务进入 FAILED，而不是让前端无限等待。</p>
     */
    @SuppressWarnings("java:S3776") // Polling state machine keeps all terminal states explicit.
    private DocForgeParseResponse parseAsync(MultipartFile file, String engine, ParseProgressListener listener) {
        String fileName = originalFileName(file);
        log.info("DocForge 异步解析: engine={}", engine);
        DocForgeJobCreateResponse created;
        try {
            created = restClient.post()
                    .uri("/v1/jobs")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(buildMultipartBody(file, engine))
                    .retrieve()
                    .body(DocForgeJobCreateResponse.class);
        } catch (RestClientResponseException e) {
            throw mapResponseException(e, "DocForge 异步任务创建失败");
        } catch (ResourceAccessException e) {
            throw mapTransportException(e, "DocForge 异步任务创建失败");
        } catch (IOException e) {
            throw new DocForgeServiceException("读取上传文件失败: " + fileName, e);
        }
        if (created == null || created.jobId() == null || created.jobId().isBlank()) {
            throw new DocForgeServiceException("DocForge 异步任务创建返回空 job_id");
        }

        long deadline = System.currentTimeMillis() + properties.asyncPollMaxWaitMs();
        while (System.currentTimeMillis() < deadline) {
            DocForgeJobDetailResponse detail = getJob(created.jobId());
            reportDocForgeProgress(listener, created.jobId(), detail);
            String status = detail.status() == null ? "" : detail.status().toLowerCase(Locale.ROOT);
            if ("succeeded".equals(status)) {
                return getJobResult(created.jobId());
            }
            if ("failed".equals(status) || "cancelled".equals(status)) {
                String error = detail.error() == null || detail.error().isBlank()
                        ? "DocForge 异步解析失败"
                        : detail.error();
                throw new DocForgeServiceException(error);
            }
            sleepPollInterval();
        }
        throw new DocForgeServiceException(
                "DocForge 异步解析超时（超过 " + properties.asyncPollMaxWaitMs() / 1000 + " 秒）");
    }

    private static void reportDocForgeProgress(
            ParseProgressListener listener,
            String jobId,
            DocForgeJobDetailResponse detail) {
        if (listener == null || detail == null || detail.progress() == null) {
            return;
        }
        DocForgeJobProgress progress = detail.progress();
        Long eta = progress.etaSeconds() == null ? null : progress.etaSeconds().longValue();
        String message = progress.message();
        if (message == null || message.isBlank()) {
            message = progress.phase();
        }
        listener.onParseProgress(progress.percent(), message, eta, jobId);
    }

    private DocForgeJobDetailResponse getJob(String jobId) {
        try {
            DocForgeJobDetailResponse detail = restClient.get()
                    .uri("/v1/jobs/{jobId}", jobId)
                    .retrieve()
                    .body(DocForgeJobDetailResponse.class);
            if (detail == null) {
                throw new DocForgeServiceException("DocForge 任务状态为空: " + jobId);
            }
            return detail;
        } catch (RestClientResponseException e) {
            throw mapResponseException(e, "查询 DocForge 任务失败");
        }
    }

    private DocForgeParseResponse getJobResult(String jobId) {
        try {
            DocForgeParseResponse result = restClient.get()
                    .uri("/v1/jobs/{jobId}/result", jobId)
                    .retrieve()
                    .body(DocForgeParseResponse.class);
            if (result == null) {
                throw new DocForgeServiceException("DocForge 任务结果为空: " + jobId);
            }
            return result;
        } catch (RestClientResponseException e) {
            throw mapResponseException(e, "获取 DocForge 任务结果失败");
        }
    }

    private MultiValueMap<String, Object> buildMultipartBody(MultipartFile file, String engine) throws IOException {
        String fileName = sanitizeFilename(originalFileName(file));
        byte[] bytes = file.getBytes();
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        body.add("engine", engine);
        body.add("output_format", properties.outputFormat());
        body.add("options", "{}");
        return body;
    }

    private DocForgeServiceException mapTransportException(ResourceAccessException e, String prefix) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return new DocForgeServiceException(
                        prefix + ": 等待 DocForge 响应超时（"
                                + properties.readTimeoutMs() / 1000
                                + " 秒）。大文件或 Maker 解析请使用异步模式，"
                                + "或增大 docforge.read-timeout-ms / docforge.async-poll-max-wait-ms",
                        HttpStatus.GATEWAY_TIMEOUT.value());
            }
            cause = cause.getCause();
        }
        return new DocForgeServiceException(prefix + ": " + e.getMessage(), e);
    }

    private DocForgeServiceException mapResponseException(RestClientResponseException e, String prefix) {
        int status = e.getStatusCode().value();
        String message = extractErrorMessage(e);
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            return new DocForgeServiceException("DocForge 鉴权失败，请检查 docforge.api-key 配置", status);
        }
        if (status == HTTP_PAYLOAD_TOO_LARGE) {
            return new DocForgeServiceException("文件超过 DocForge 大小限制: " + message, status);
        }
        if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) {
            return new DocForgeServiceException("DocForge 不支持该文件格式: " + message, HttpStatus.BAD_REQUEST.value());
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value() || status >= 500) {
            return new DocForgeServiceException(prefix + ": " + humanizeServerError(message), status);
        }
        return new DocForgeServiceException(prefix + ": " + message, HttpStatus.BAD_REQUEST.value());
    }

    private static String humanizeServerError(String message) {
        if (message == null || message.isBlank()
                || "Internal server error".equalsIgnoreCase(message)) {
            return "DocForge 内部错误。Docling 首次解析 PDF 需从 HuggingFace 下载模型，"
                    + "请检查网络/代理，或查看 .runtime/docforge.log；"
                    + "也可改用 Maker 解析或等待其他解析任务完成";
        }
        return message;
    }

    private String extractErrorMessage(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body.isBlank()) {
            return e.getMessage();
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.hasNonNull("message")) {
                return node.get("message").asText();
            }
            if (node.hasNonNull("detail")) {
                JsonNode detail = node.get("detail");
                return detail.isTextual() ? detail.asText() : detail.toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private void sleepPollInterval() {
        try {
            TimeUnit.MILLISECONDS.sleep(properties.asyncPollIntervalMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocForgeServiceException("DocForge 异步解析被中断", e);
        }
    }

    private static String originalFileName(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        return fileName == null || fileName.isBlank() ? UNKNOWN : fileName;
    }

    static String sanitizeFilename(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return UNKNOWN;
        }
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String leaf = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String sanitized = leaf.replaceAll("[\\r\\n]", "_");
        return sanitized.isBlank() ? UNKNOWN : sanitized;
    }

    private DocForgeFormatsResponse fallbackFormats(String engine) {
        return new DocForgeFormatsResponse(
                engine,
                FALLBACK_EXTENSIONS,
                List.of("markdown", "html", "json", "doctags"),
                properties.asyncMaxSizeMb(),
                properties.syncMaxSizeMb());
    }

    private static boolean cacheExpired(long cachedAt) {
        return cachedAt <= 0 || System.currentTimeMillis() - cachedAt >= CAPABILITY_CACHE_TTL_MS;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReadyResponse(
            String status,
            @JsonProperty("engine_initialized") boolean engineInitialized,
            List<String> engines) {
    }
}
