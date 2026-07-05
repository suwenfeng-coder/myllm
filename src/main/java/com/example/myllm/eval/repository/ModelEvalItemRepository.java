package com.example.myllm.eval.repository;

import com.example.myllm.eval.entity.EvalItemStatus;
import com.example.myllm.eval.entity.ModelEvalItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelEvalItemRepository extends JpaRepository<ModelEvalItem, Long> {

    List<ModelEvalItem> findByRunIdOrderByItemIndexAsc(String runId);

    Optional<ModelEvalItem> findFirstByRunIdAndStatusOrderByItemIndexAsc(String runId, EvalItemStatus status);

    long countByRunIdAndStatus(String runId, EvalItemStatus status);
}
