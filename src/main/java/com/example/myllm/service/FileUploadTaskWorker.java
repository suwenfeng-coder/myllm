package com.example.myllm.service;

import com.example.myllm.dto.FileEmbeddingResponse;
import com.example.myllm.entity.DocumentUploadTask;
import com.example.myllm.support.upload.StoredUploadFile;
import com.example.myllm.support.upload.UploadProgressReporter;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异步消费上传向量化任务，避免长时间占用 HTTP 线程。
 *
 * <p>Worker 每次调度只领取一条任务，适合本地 Demo 和单机开发；如果后续提高并发度，需要配套 embedding
 * 限流、DocForge 并发控制和任务 lease/fencing，避免多个长任务互相拖垮本地模型服务。</p>
 */
@Component
@ConditionalOnProperty(prefix = "upload.task", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FileUploadTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(FileUploadTaskWorker.class);

    private final FileUploadTaskService taskService;
    private final FileEmbeddingService fileEmbeddingService;

    public FileUploadTaskWorker(
            FileUploadTaskService taskService,
            FileEmbeddingService fileEmbeddingService) {
        this.taskService = taskService;
        this.fileEmbeddingService = fileEmbeddingService;
    }

    /**
     * 定时恢复超时任务并领取下一条待处理任务。
     *
     * <p>这里不在同一个事务里执行文件解析/向量化；领取任务后立即释放 MySQL 锁，实际长耗时工作交给
     * {@link #execute(DocumentUploadTask)}。</p>
     */
    @Scheduled(fixedDelayString = "${upload.task.poll-interval-ms:2000}")
    public void processNext() {
        int recovered = taskService.recoverTimedOutTasks();
        if (recovered > 0) {
            log.warn("已终止超时上传任务 count={}", recovered);
        }
        taskService.claimNext().ifPresent(this::execute);
    }

    @Scheduled(fixedDelayString = "${upload.task.cleanup-interval-ms:3600000}")
    public void cleanupExpiredTasks() {
        int deleted = taskService.cleanupExpiredTasks();
        if (deleted > 0) {
            log.info("已清理过期上传任务记录 count={}", deleted);
        }
    }

    /**
     * 执行完整入库流水线，并把业务进度桥接回任务表。
     *
     * <p>{@link StoredUploadFile} 让已经落盘的临时文件重新表现为 MultipartFile，从而复用同步入库代码路径，
     * 保证同步/异步上传的一致性。</p>
     */
    private void execute(DocumentUploadTask task) {
        long startNanos = System.nanoTime();
        String taskId = task.getTaskId();
        try {
            StoredUploadFile file = new StoredUploadFile(
                    Path.of(task.getTempFilePath()),
                    task.getFileName(),
                    task.getContentType());
            UploadProgressReporter reporter = (status, phase, percent, message, etaSeconds, docforgeJobId) ->
                    taskService.updateProgress(taskId, status, phase, percent, message, etaSeconds, docforgeJobId);

            FileEmbeddingService.EmbedPipelineOutcome outcome = fileEmbeddingService.embedAndStoreWithProgress(
                    file,
                    task.getChunkStrategy(),
                    task.getParseMode(),
                    reporter);
            FileEmbeddingResponse response = outcome.response();
            taskService.markSuccess(taskId, response, outcome.graphIndexEnqueued(), outcome.fileId());
            log.info("上传任务成功 taskId={} fileId={} durationMs={} graphEnqueued={}",
                    taskId,
                    outcome.fileId(),
                    elapsedMillis(startNanos),
                    outcome.graphIndexEnqueued());
        } catch (Exception e) {
            taskService.markFailure(taskId, e);
            log.warn("上传任务失败 taskId={} durationMs={} error={}",
                    taskId, elapsedMillis(startNanos), e.getMessage());
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
