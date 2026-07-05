package com.example.myllm.repository;

import com.example.myllm.entity.DocumentUploadTask;
import com.example.myllm.entity.UploadTaskStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 文档上传向量化任务数据访问。 */
public interface DocumentUploadTaskRepository extends JpaRepository<DocumentUploadTask, String> {

    @Query(value = "SELECT * FROM document_upload_task"
            + " WHERE status = 'PENDING'"
            + " ORDER BY created_at, task_id"
            + " LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<DocumentUploadTask> lockNextPendingTask();

    @Modifying
    @Query(value = "UPDATE document_upload_task"
            + " SET status = 'FAILED', phase = 'COMPLETED', message = '任务执行超时',"
            + " error_message = '任务执行超时，已自动终止', finished_at = CURRENT_TIMESTAMP(6),"
            + " updated_at = CURRENT_TIMESTAMP(6)"
            + " WHERE status = 'RUNNING' AND started_at < :cutoff", nativeQuery = true)
    int failTimedOutRunningTasks(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query(value = "UPDATE document_upload_task"
            + " SET status = 'FAILED', phase = 'COMPLETED', message = '排队超时',"
            + " error_message = '任务长时间未开始执行，已自动终止', finished_at = CURRENT_TIMESTAMP(6),"
            + " updated_at = CURRENT_TIMESTAMP(6)"
            + " WHERE status = 'PENDING' AND created_at < :cutoff", nativeQuery = true)
    int failStalePendingTasks(@Param("cutoff") LocalDateTime cutoff);

    List<DocumentUploadTask> findByStatusInAndFinishedAtBefore(
            List<UploadTaskStatus> statuses, LocalDateTime cutoff);
}
