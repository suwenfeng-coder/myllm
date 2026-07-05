package com.example.myllm.repository;

import com.example.myllm.entity.TransactionLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {
    List<TransactionLog> findTop100ByOrderByIdDesc();

    List<TransactionLog> findAllByOrderByIdDesc(Pageable pageable);

    List<TransactionLog> findByCreatedAtBetweenOrderByIdDesc(
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable);
}
