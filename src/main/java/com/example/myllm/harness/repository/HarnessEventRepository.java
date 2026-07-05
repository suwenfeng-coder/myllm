package com.example.myllm.harness.repository;

import com.example.myllm.harness.entity.HarnessEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Harness 事件数据访问。 */
public interface HarnessEventRepository extends JpaRepository<HarnessEvent, String> {

    List<HarnessEvent> findByRunIdOrderBySequenceNoAsc(String runId);

    @Query("SELECT COALESCE(MAX(e.sequenceNo), 0) FROM HarnessEvent e WHERE e.runId = :runId")
    int findMaxSequenceNo(@Param("runId") String runId);
}
