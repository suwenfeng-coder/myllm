package com.example.myllm.service;

import com.example.myllm.config.GraphProperties;
import com.example.myllm.dto.GraphIndexTaskResponse;
import com.example.myllm.entity.DocumentGraphIndexTask;
import com.example.myllm.entity.GraphIndexTaskOperation;
import com.example.myllm.entity.GraphIndexTaskStatus;
import com.example.myllm.repository.DocumentGraphIndexTaskRepository;
import com.example.myllm.support.graph.GraphIndexingResult;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Neo4j 构图任务的创建、领取、重试和状态查询服务。
 *
 * <p>事务只覆盖 MySQL 任务状态；实际 Neo4j 写入由 Worker 在事务外执行，避免长事务
 * 占用 MySQL 连接和行锁。</p>
 */
@Service
public class GraphIndexTaskService {

    private static final int MAX_ERROR_LENGTH = 4000;

    private final DocumentGraphIndexTaskRepository repository;
    private final GraphProperties properties;

    public GraphIndexTaskService(
            DocumentGraphIndexTaskRepository repository,
            GraphProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 向量入库完成后创建幂等 INDEX 任务；图能力关闭时直接忽略。
     */
    @Transactional
    public void enqueueIndex(String fileId, String fileName) {
        if (!properties.isEnabled()) {
            return;
        }
        upsert(fileId, normalizeFileName(fileId, fileName), GraphIndexTaskOperation.INDEX);
    }

    /**
     * 文件删除后取消未执行的 INDEX，并创建幂等 DELETE 任务。
     */
    @Transactional
    public void enqueueDelete(String fileId, String fileName) {
        if (!properties.isEnabled()) {
            return;
        }
        String normalizedFileId = requireFileId(fileId);
        repository.cancelPendingIndexTasks(normalizedFileId);
        upsert(normalizedFileId, normalizeFileName(fileId, fileName), GraphIndexTaskOperation.DELETE);
    }

    /** 原子领取一个可执行任务，并在事务提交前标记为 RUNNING。 */
    @Transactional
    public Optional<DocumentGraphIndexTask> claimNext() {
        return repository.lockNextReadyTask(properties.getIndexing().getMaxAttempts())
                .map(task -> {
                    task.setStatus(GraphIndexTaskStatus.RUNNING);
                    task.setAttemptCount(safe(task.getAttemptCount()) + 1);
                    task.setStartedAt(LocalDateTime.now(ZoneId.systemDefault()));
                    task.setFinishedAt(null);
                    task.setNextRetryAt(null);
                    task.setErrorMessage(null);
                    return repository.saveAndFlush(task);
                });
    }

    /** 将长时间未完成的 RUNNING 任务恢复为可重试状态。 */
    @Transactional
    public int recoverTimedOutTasks() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.systemDefault()).minusNanos(
                properties.getIndexing().getRunningTimeoutMs() * 1_000_000L);
        return repository.recoverTimedOutTasks(cutoff);
    }

    /** 记录构图成功的节点和关系数量。 */
    @Transactional
    public void markSuccess(long taskId, GraphIndexingResult result) {
        DocumentGraphIndexTask task = requireTask(taskId);
        task.setStatus(GraphIndexTaskStatus.SUCCESS);
        task.setNodeCount(result == null ? 0 : Math.max(0, result.nodeCount()));
        task.setRelationshipCount(result == null ? 0 : Math.max(0, result.relationshipCount()));
        task.setErrorMessage(null);
        task.setNextRetryAt(null);
        task.setFinishedAt(LocalDateTime.now(ZoneId.systemDefault()));
        repository.save(task);
    }

    /**
     * 记录失败并按指数退避重试；达到最大次数后进入 DEAD，不再自动执行。
     */
    @Transactional
    public void markFailure(long taskId, Throwable error) {
        DocumentGraphIndexTask task = requireTask(taskId);
        int attempts = safe(task.getAttemptCount());
        task.setErrorMessage(safeError(error));
        if (attempts >= properties.getIndexing().getMaxAttempts()) {
            task.setStatus(GraphIndexTaskStatus.DEAD);
            task.setNextRetryAt(null);
            task.setFinishedAt(LocalDateTime.now(ZoneId.systemDefault()));
        } else {
            task.setStatus(GraphIndexTaskStatus.FAILED);
            task.setNextRetryAt(LocalDateTime.now(ZoneId.systemDefault())
                    .plusNanos(retryDelayMs(attempts) * 1_000_000L));
            task.setFinishedAt(null);
        }
        repository.save(task);
    }

    /** 手工将最近的失败或死信任务恢复为 PENDING。 */
    @Transactional
    public GraphIndexTaskResponse retry(String fileId) {
        DocumentGraphIndexTask task = repository.findTopByFileIdOrderByIdDesc(requireFileId(fileId))
                .orElseThrow(() -> new IllegalArgumentException("未找到构图任务: " + fileId));
        if (task.getStatus() == GraphIndexTaskStatus.RUNNING) {
            throw new IllegalStateException("构图任务正在执行，不能重复提交");
        }
        task.setStatus(GraphIndexTaskStatus.PENDING);
        task.setAttemptCount(0);
        task.setErrorMessage(null);
        task.setNextRetryAt(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        return toResponse(repository.save(task));
    }

    /** 查询文件最近一笔构图或图删除任务。 */
    @Transactional(readOnly = true)
    public Optional<GraphIndexTaskResponse> findLatest(String fileId) {
        return repository.findTopByFileIdOrderByIdDesc(requireFileId(fileId))
                .map(GraphIndexTaskService::toResponse);
    }

    private void upsert(String fileId, String fileName, GraphIndexTaskOperation operation) {
        String normalizedFileId = requireFileId(fileId);
        String version = extractionVersion();
        DocumentGraphIndexTask task = repository
                .findByFileIdAndExtractionVersionAndOperation(normalizedFileId, version, operation)
                .orElseGet(DocumentGraphIndexTask::new);
        task.setFileId(normalizedFileId);
        task.setFileName(fileName);
        task.setOperation(operation);
        task.setStatus(GraphIndexTaskStatus.PENDING);
        task.setExtractionVersion(version);
        task.setAttemptCount(0);
        task.setNodeCount(0);
        task.setRelationshipCount(0);
        task.setErrorMessage(null);
        task.setNextRetryAt(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        repository.save(task);
    }

    private DocumentGraphIndexTask requireTask(long taskId) {
        return repository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("构图任务不存在: " + taskId));
    }

    private long retryDelayMs(int attempts) {
        long initial = properties.getIndexing().getRetryInitialDelayMs();
        int exponent = Math.max(0, Math.min(20, attempts - 1));
        long factor = 1L << exponent;
        long calculated;
        try {
            calculated = Math.multiplyExact(initial, factor);
        } catch (ArithmeticException ignored) {
            calculated = Long.MAX_VALUE;
        }
        return Math.min(calculated, properties.getIndexing().getRetryMaxDelayMs());
    }

    private String extractionVersion() {
        if (properties.getIndexing().isEntityExtractionEnabled()) {
            String entityVersion = properties.getIndexing().getEntityExtractionVersion();
            if (entityVersion != null && !entityVersion.isBlank()) {
                return entityVersion.trim();
            }
        }
        String version = properties.getIndexing().getExtractionVersion();
        return version == null || version.isBlank() ? "structure-v1" : version.trim();
    }

    private static String requireFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId 不能为空");
        }
        return fileId.trim();
    }

    private static String normalizeFileName(String fileId, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return requireFileId(fileId);
        }
        String normalized = fileName.trim();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    private static int safe(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static String safeError(Throwable error) {
        if (error == null) {
            return "UNKNOWN_GRAPH_INDEX_ERROR";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getClass().getSimpleName()
                + (root.getMessage() == null || root.getMessage().isBlank() ? "" : ":" + root.getMessage());
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private static GraphIndexTaskResponse toResponse(DocumentGraphIndexTask task) {
        return new GraphIndexTaskResponse(
                task.getId(),
                task.getFileId(),
                task.getOperation().name(),
                task.getStatus().name(),
                task.getExtractionVersion(),
                safe(task.getAttemptCount()),
                safe(task.getNodeCount()),
                safe(task.getRelationshipCount()),
                task.getErrorMessage(),
                task.getNextRetryAt(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getUpdatedAt());
    }
}
