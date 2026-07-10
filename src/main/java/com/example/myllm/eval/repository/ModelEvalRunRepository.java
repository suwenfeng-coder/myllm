package com.example.myllm.eval.repository;

import com.example.myllm.eval.entity.ModelEvalRun;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelEvalRunRepository extends JpaRepository<ModelEvalRun, String> {

    List<ModelEvalRun> findTop50ByOrderByCreatedAtDesc();

    List<ModelEvalRun> findBySuiteIdOrderByCreatedAtDesc(Long suiteId);

    @Query(value = "SELECT * FROM model_eval_run"
            + " WHERE status = 'PENDING'"
            + " ORDER BY created_at, run_id"
            + " LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<ModelEvalRun> lockNextPendingRun();

    @Query(value = "SELECT * FROM model_eval_run"
            + " WHERE status IN ('PENDING', 'RUNNING')"
            + " ORDER BY CASE WHEN status = 'RUNNING' THEN 0 ELSE 1 END, created_at, run_id"
            + " LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<ModelEvalRun> lockNextRunnableRun();

    @Modifying
    @Query(value = "UPDATE model_eval_run"
            + " SET status = 'FAILED', error_message = '评测执行超时', finished_at = CURRENT_TIMESTAMP(6),"
            + " updated_at = CURRENT_TIMESTAMP(6)"
            + " WHERE status = 'RUNNING' AND started_at < :cutoff", nativeQuery = true)
    int failTimedOutRunningRuns(@Param("cutoff") LocalDateTime cutoff);
}
