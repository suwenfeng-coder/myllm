package com.example.myllm.eval.repository;

import com.example.myllm.eval.entity.ModelEvalQuestion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelEvalQuestionRepository extends JpaRepository<ModelEvalQuestion, Long> {

    List<ModelEvalQuestion> findBySuiteIdOrderBySortOrderAscIdAsc(Long suiteId);

    List<ModelEvalQuestion> findBySuiteIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long suiteId);

    void deleteBySuiteId(Long suiteId);
}
