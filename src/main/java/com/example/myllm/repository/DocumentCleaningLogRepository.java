package com.example.myllm.repository;

import com.example.myllm.entity.DocumentCleaningLog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 文档清理与分块审计日志的数据访问接口。
 */
public interface DocumentCleaningLogRepository extends JpaRepository<DocumentCleaningLog, Long> {

    /**
     * @param fileId 向量文件唯一标识
     * @return 对应的清理日志；旧数据不存在时为空
     */
    Optional<DocumentCleaningLog> findByFileId(String fileId);

    long deleteByFileId(String fileId);
}
