package com.example.myllm.harness.repository;

import com.example.myllm.harness.entity.HarnessRun;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/** Harness 运行数据访问。 */
public interface HarnessRunRepository extends JpaRepository<HarnessRun, String> {

    Optional<HarnessRun> findByClientRequestId(String clientRequestId);

    /** 串行化同一 Run 的事件序号分配，避免取消与 Worker 并发写事件时序号冲突。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM HarnessRun r WHERE r.runId = :runId")
    Optional<HarnessRun> lockById(@Param("runId") String runId);

    @Query(value = "SELECT * FROM harness_run WHERE status = 'QUEUED'"
            + " ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<HarnessRun> lockNextQueued();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE harness_run SET status = 'QUEUED', lease_owner = NULL, lease_token = NULL,"
            + " lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP"
            + " WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL"
            + " AND lease_expires_at < :now", nativeQuery = true)
    int recoverExpiredLeases(@Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE harness_run SET lease_expires_at = :expiresAt, updated_at = CURRENT_TIMESTAMP"
            + " WHERE run_id = :runId AND lease_token = :leaseToken AND status = 'RUNNING'", nativeQuery = true)
    int heartbeat(
            @Param("runId") String runId,
            @Param("leaseToken") String leaseToken,
            @Param("expiresAt") LocalDateTime expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE harness_run SET status = :status, final_output_preview = :preview,"
            + " final_artifact_id = :artifactId, finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,"
            + " lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL"
            + " WHERE run_id = :runId AND lease_token = :leaseToken AND status = 'RUNNING'", nativeQuery = true)
    int completeWithLease(
            @Param("runId") String runId,
            @Param("leaseToken") String leaseToken,
            @Param("status") String status,
            @Param("preview") String preview,
            @Param("artifactId") String artifactId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE harness_run SET status = 'FAILED', error_code = :errorCode, error_message = :errorMessage,"
            + " finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,"
            + " lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL"
            + " WHERE run_id = :runId AND lease_token = :leaseToken AND status = 'RUNNING'", nativeQuery = true)
    int failWithLease(
            @Param("runId") String runId,
            @Param("leaseToken") String leaseToken,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE harness_run SET status = 'CANCELLED', cancel_requested = 1,"
            + " error_code = 'RUN_CANCELLED', error_message = :message,"
            + " finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,"
            + " lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL"
            + " WHERE run_id = :runId AND lease_token = :leaseToken AND status = 'RUNNING'", nativeQuery = true)
    int cancelWithLease(
            @Param("runId") String runId,
            @Param("leaseToken") String leaseToken,
            @Param("message") String message);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE harness_run SET status = 'CANCELLED', cancel_requested = 1,"
            + " error_code = 'RUN_CANCELLED', error_message = :message,"
            + " finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP"
            + " WHERE run_id = :runId AND status IN ('CREATED','QUEUED','PLANNING','WAITING_APPROVAL','VERIFYING')",
            nativeQuery = true)
    int cancelBeforeExecution(@Param("runId") String runId, @Param("message") String message);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE harness_run SET cancel_requested = 1, updated_at = CURRENT_TIMESTAMP"
            + " WHERE run_id = :runId AND status = 'RUNNING'",
            nativeQuery = true)
    int requestCancel(@Param("runId") String runId);
}
