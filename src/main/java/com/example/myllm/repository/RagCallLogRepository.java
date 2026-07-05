package com.example.myllm.repository;

import com.example.myllm.entity.RagCallLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagCallLogRepository extends JpaRepository<RagCallLog, Long> {
    List<RagCallLog> findByTransactionLogIdIn(List<Long> transactionLogIds);
}
