package com.example.myllm.repository;

import com.example.myllm.entity.DocumentGraphIndexTask;
import com.example.myllm.entity.GraphIndexTaskOperation;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Neo4j 构图任务数据访问接口。 */
public interface DocumentGraphIndexTaskRepository extends JpaRepository<DocumentGraphIndexTask, Long> {

    Optional<DocumentGraphIndexTask> findByFileIdAndExtractionVersionAndOperation(
            String fileId,
            String extractionVersion,
            GraphIndexTaskOperation operation);

    Optional<DocumentGraphIndexTask> findTopByFileIdOrderByIdDesc(String fileId);

    /**
     * 使用 MySQL 8 的 SKIP LOCKED 原子领取一个可执行任务，支持未来多实例并发消费。
     */
    @Query(value = "SELECT * FROM document_graph_index_task"
            + " WHERE status IN ('PENDING', 'FAILED')"
            + " AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP(6))"
            + " AND attempt_count < :maxAttempts"
            + " ORDER BY COALESCE(next_retry_at, created_at), id"
            + " LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<DocumentGraphIndexTask> lockNextReadyTask(@Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(value = "UPDATE document_graph_index_task"
            + " SET status = 'FAILED', error_message = 'WORKER_TIMEOUT_RECOVERED',"
            + " next_retry_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)"
            + " WHERE status = 'RUNNING' AND started_at < :cutoff", nativeQuery = true)
    int recoverTimedOutTasks(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query(value = "UPDATE document_graph_index_task"
            + " SET status = 'CANCELED', error_message = 'FILE_DELETE_REQUESTED',"
            + " finished_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)"
            + " WHERE file_id = :fileId AND operation = 'INDEX'"
            + " AND status IN ('PENDING', 'FAILED')", nativeQuery = true)
    int cancelPendingIndexTasks(@Param("fileId") String fileId);
}
