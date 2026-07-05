package com.example.myllm.repository;

import com.example.myllm.entity.DocumentStorageLog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentStorageLogRepository extends JpaRepository<DocumentStorageLog, Long> {

    Optional<DocumentStorageLog> findByFileId(String fileId);

    long deleteByFileId(String fileId);
}
