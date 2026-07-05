package com.example.myllm.service;

import com.example.myllm.entity.DocumentGraphIndexTask;
import com.example.myllm.entity.GraphIndexTaskOperation;
import com.example.myllm.support.graph.GraphIndexingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 持久化构图任务消费者。
 *
 * <p>单次调度只领取一个任务；多实例通过 MySQL {@code FOR UPDATE SKIP LOCKED} 避免重复领取。</p>
 */
@Component
@ConditionalOnExpression("'${graph.enabled:false}' == 'true' && '${graph.indexing.enabled:false}' == 'true'")
public class GraphIndexTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(GraphIndexTaskWorker.class);

    private final GraphIndexTaskService taskService;
    private final GraphIndexingService indexingService;

    public GraphIndexTaskWorker(
            GraphIndexTaskService taskService,
            GraphIndexingService indexingService) {
        this.taskService = taskService;
        this.indexingService = indexingService;
    }

    /** 恢复超时任务并处理下一条待执行任务。 */
    @Scheduled(fixedDelayString = "${graph.indexing.task-poll-interval-ms:5000}")
    public void processNext() {
        int recovered = taskService.recoverTimedOutTasks();
        if (recovered > 0) {
            log.warn("已恢复超时 Neo4j 构图任务 count={}", recovered);
        }
        taskService.claimNext().ifPresent(this::execute);
    }

    private void execute(DocumentGraphIndexTask task) {
        long startNanos = System.nanoTime();
        try {
            GraphIndexingResult result = task.getOperation() == GraphIndexTaskOperation.DELETE
                    ? indexingService.deleteFile(task.getFileId())
                    : indexingService.indexFile(task.getFileId());
            taskService.markSuccess(task.getId(), result);
        log.info("Neo4j 图任务成功 taskId={} operation={} fileId={} nodes={} relationships={} entities={} mentions={} entityRelations={} durationMs={}",
                task.getId(), task.getOperation(), task.getFileId(), result.nodeCount(),
                result.relationshipCount(), result.entityCount(), result.mentionCount(),
                result.relationCount(), elapsedMillis(startNanos));
        } catch (Exception e) {
            taskService.markFailure(task.getId(), e);
            log.warn("Neo4j 图任务失败 taskId={} operation={} fileId={} durationMs={} error={}",
                    task.getId(), task.getOperation(), task.getFileId(), elapsedMillis(startNanos), e.getMessage());
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
