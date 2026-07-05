package com.example.myllm.service;

import com.example.myllm.config.UploadTaskProperties;
import com.example.myllm.dto.FileEmbeddingResponse;
import com.example.myllm.dto.ParseEstimateResponse;
import com.example.myllm.dto.UploadTaskCreateResponse;
import com.example.myllm.dto.UploadTaskStatusResponse;
import com.example.myllm.entity.DocumentUploadTask;
import com.example.myllm.entity.UploadTaskPhase;
import com.example.myllm.entity.UploadTaskStatus;
import com.example.myllm.repository.DocumentUploadTaskRepository;
import com.example.myllm.support.FileTextExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 异步上传任务创建、进度更新与查询。 */
@Service
public class FileUploadTaskService {

    private static final int MAX_ERROR_LENGTH = 4000;

    private final DocumentUploadTaskRepository repository;
    private final UploadTaskProperties properties;
    private final ParseEstimateService parseEstimateService;
    private final ObjectMapper objectMapper;

    public FileUploadTaskService(
            DocumentUploadTaskRepository repository,
            UploadTaskProperties properties,
            ParseEstimateService parseEstimateService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.parseEstimateService = parseEstimateService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UploadTaskCreateResponse createTask(
            MultipartFile file, String chunkStrategy, String parseMode) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("异步上传任务未启用");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String taskId = UUID.randomUUID().toString();
        String originalFileName = file.getOriginalFilename();
        String fileName = originalFileName == null ? "unknown" : originalFileName;
        Path tempDir = Path.of(properties.getTempDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(tempDir);
            Path tempFile = tempDir.resolve(taskId + "-" + sanitizeFileName(fileName));
            file.transferTo(tempFile);

            DocumentUploadTask task = new DocumentUploadTask();
            task.setTaskId(taskId);
            task.setFileName(fileName);
            task.setTempFilePath(tempFile.toString());
            task.setContentType(file.getContentType());
            task.setFileSizeBytes(file.getSize());
            task.setChunkStrategy(normalizeChunkStrategy(chunkStrategy));
            task.setParseMode(normalizeParseMode(parseMode));
            task.setStatus(UploadTaskStatus.PENDING);
            task.setPhase(UploadTaskPhase.QUEUED);
            task.setPercent(0);
            task.setMessage("任务已入队，等待处理");
            task.setGraphIndexEnqueued(false);
            repository.save(task);

            ParseEstimateResponse estimate = parseEstimateService.estimate(task.getParseMode(), file.getSize());
            return new UploadTaskCreateResponse(
                    taskId,
                    estimate.etaSeconds(),
                    "上传任务已创建，请轮询 /api/files/tasks/" + taskId);
        } catch (IOException e) {
            throw new IllegalStateException("保存上传临时文件失败: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<UploadTaskStatusResponse> findStatus(String taskId) {
        return repository.findById(requireTaskId(taskId)).map(this::toStatusResponse);
    }

    @Transactional(readOnly = true)
    public Optional<FileEmbeddingResponse> findResult(String taskId) {
        DocumentUploadTask task = repository.findById(requireTaskId(taskId)).orElse(null);
        if (task == null || task.getStatus() != UploadTaskStatus.SUCCEEDED || task.getResultJson() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(task.getResultJson(), FileEmbeddingResponse.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析上传任务结果失败: " + taskId, e);
        }
    }

    @Transactional
    public Optional<DocumentUploadTask> claimNext() {
        return repository.lockNextPendingTask().map(task -> {
            task.setStatus(UploadTaskStatus.RUNNING);
            task.setPhase(UploadTaskPhase.PARSING);
            task.setPercent(1);
            task.setMessage("开始处理");
            task.setStartedAt(LocalDateTime.now(ZoneId.systemDefault()));
            task.setFinishedAt(null);
            task.setErrorMessage(null);
            return repository.saveAndFlush(task);
        });
    }

    @Transactional
    public int recoverTimedOutTasks() {
        LocalDateTime runningCutoff =
                LocalDateTime.now(ZoneId.systemDefault())
                        .minusNanos(properties.getRunningTimeoutMs() * 1_000_000L);
        LocalDateTime pendingCutoff =
                LocalDateTime.now(ZoneId.systemDefault())
                        .minusNanos(properties.getPendingTimeoutMs() * 1_000_000L);
        return repository.failTimedOutRunningTasks(runningCutoff)
                + repository.failStalePendingTasks(pendingCutoff);
    }

    @Transactional
    public int cleanupExpiredTasks() {
        LocalDateTime retentionCutoff =
                LocalDateTime.now(ZoneId.systemDefault()).minusHours(properties.getRetentionHours());
        List<DocumentUploadTask> expired = repository.findByStatusInAndFinishedAtBefore(
                List.of(
                        UploadTaskStatus.SUCCEEDED,
                        UploadTaskStatus.FAILED,
                        UploadTaskStatus.CANCELLED),
                retentionCutoff);
        int deleted = 0;
        for (DocumentUploadTask task : expired) {
            deleteTempFileQuietly(task);
            repository.delete(task);
            deleted += 1;
        }
        return deleted;
    }

    @Transactional
    public void updateProgress(
            String taskId,
            UploadTaskStatus status,
            UploadTaskPhase phase,
            int percent,
            String message,
            Long etaSeconds,
            String docforgeJobId) {
        DocumentUploadTask task = requireTask(taskId);
        if (task.getStatus() == UploadTaskStatus.CANCELLED
                || task.getStatus() == UploadTaskStatus.SUCCEEDED
                || task.getStatus() == UploadTaskStatus.FAILED) {
            return;
        }
        task.setStatus(status);
        task.setPhase(phase);
        task.setPercent(Math.max(0, Math.min(100, percent)));
        task.setMessage(message);
        task.setEtaSeconds(etaSeconds);
        if (docforgeJobId != null && !docforgeJobId.isBlank()) {
            task.setDocforgeJobId(docforgeJobId);
        }
        repository.save(task);
    }

    @Transactional
    public void markSuccess(
            String taskId,
            FileEmbeddingResponse response,
            boolean graphIndexEnqueued,
            String fileId) {
        DocumentUploadTask task = requireTask(taskId);
        task.setStatus(UploadTaskStatus.SUCCEEDED);
        task.setPhase(UploadTaskPhase.COMPLETED);
        task.setPercent(100);
        task.setMessage(response.message());
        task.setEtaSeconds(0L);
        task.setFileId(fileId);
        task.setGraphIndexEnqueued(graphIndexEnqueued);
        task.setFinishedAt(LocalDateTime.now(ZoneId.systemDefault()));
        task.setErrorMessage(null);
        try {
            task.setResultJson(objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化上传任务结果失败: " + taskId, e);
        }
        repository.save(task);
        deleteTempFileQuietly(task);
    }

    @Transactional
    public void markFailure(String taskId, Throwable error) {
        DocumentUploadTask task = requireTask(taskId);
        task.setStatus(UploadTaskStatus.FAILED);
        task.setPhase(UploadTaskPhase.COMPLETED);
        task.setErrorMessage(safeError(error));
        task.setMessage("处理失败");
        task.setFinishedAt(LocalDateTime.now(ZoneId.systemDefault()));
        repository.save(task);
        deleteTempFileQuietly(task);
    }

    @Transactional
    public void cancel(String taskId) {
        DocumentUploadTask task = requireTask(taskId);
        if (task.getStatus() == UploadTaskStatus.SUCCEEDED || task.getStatus() == UploadTaskStatus.FAILED) {
            throw new IllegalStateException("任务已结束，无法取消");
        }
        task.setStatus(UploadTaskStatus.CANCELLED);
        task.setPhase(UploadTaskPhase.COMPLETED);
        task.setMessage("任务已取消");
        task.setFinishedAt(LocalDateTime.now(ZoneId.systemDefault()));
        repository.save(task);
        deleteTempFileQuietly(task);
    }

    public boolean shouldUseAsyncUpload(MultipartFile file, String parseMode) {
        if (!properties.isEnabled()) {
            return false;
        }
        if (file == null) {
            return false;
        }
        String mode = normalizeParseMode(parseMode);
        if ("maker".equals(mode)) {
            return true;
        }
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = FileTextExtractor.extensionOf(fileName);
        if ("pdf".equals(extension) && ("docling".equals(mode) || "auto".equals(mode))) {
            return true;
        }
        return file.getSize() > properties.getAsyncThresholdBytes();
    }

    private UploadTaskStatusResponse toStatusResponse(DocumentUploadTask task) {
        return new UploadTaskStatusResponse(
                task.getTaskId(),
                task.getStatus().name(),
                task.getPhase().name(),
                task.getPercent() == null ? 0 : task.getPercent(),
                task.getMessage(),
                task.getEtaSeconds(),
                task.getFileId(),
                task.getDocforgeJobId(),
                Boolean.TRUE.equals(task.getGraphIndexEnqueued()),
                task.getErrorMessage());
    }

    private DocumentUploadTask requireTask(String taskId) {
        return repository.findById(requireTaskId(taskId))
                .orElseThrow(() -> new IllegalArgumentException("未找到上传任务: " + taskId));
    }

    private static String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        return taskId.trim();
    }

    private static String normalizeChunkStrategy(String chunkStrategy) {
        return chunkStrategy == null || chunkStrategy.isBlank() ? "fixed" : chunkStrategy.trim();
    }

    private static String normalizeParseMode(String parseMode) {
        if (parseMode == null || parseMode.isBlank()) {
            return "auto";
        }
        String normalized = parseMode.trim().toLowerCase(Locale.ROOT);
        return "marker".equals(normalized) ? "maker" : normalized;
    }

    private static String sanitizeFileName(String fileName) {
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String leaf = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return leaf.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String safeError(Throwable error) {
        String message = error == null ? "未知错误" : error.getMessage();
        if (message == null || message.isBlank()) {
            message = error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private void deleteTempFileQuietly(DocumentUploadTask task) {
        if (task.getTempFilePath() == null || task.getTempFilePath().isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(task.getTempFilePath()));
        } catch (IOException ignored) {
            // best effort
        }
    }
}
