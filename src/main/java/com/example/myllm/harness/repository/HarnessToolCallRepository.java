package com.example.myllm.harness.repository;

import com.example.myllm.harness.entity.HarnessToolCall;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HarnessToolCallRepository extends JpaRepository<HarnessToolCall, String> {

    Optional<HarnessToolCall> findByToolNameAndIdempotencyKey(String toolName, String idempotencyKey);
}
