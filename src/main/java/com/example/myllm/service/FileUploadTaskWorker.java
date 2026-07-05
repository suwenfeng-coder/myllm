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

/** 异步消费上传向量化任务，避免长时间占用 HTTP 线程。 */
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
