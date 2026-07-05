package com.example.myllm.harness.repository;

import com.example.myllm.harness.entity.HarnessStep;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Harness 步骤数据访问。 */
public interface HarnessStepRepository extends JpaRepository<HarnessStep, String> {

    List<HarnessStep> findByRunIdOrderBySequenceNoAsc(String runId);

    @Query("SELECT COALESCE(MAX(s.sequenceNo), 0) FROM HarnessStep s WHERE s.runId = :runId")
    int findMaxSequenceNo(@Param("runId") String runId);
}
