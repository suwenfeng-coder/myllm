package com.example.myllm.eval.service;

import com.example.myllm.eval.dto.EvalApiModels.EvalDimensionScores;
import com.example.myllm.eval.entity.ModelEvalItem;
import com.example.myllm.eval.entity.ModelEvalRun;
import com.example.myllm.eval.repository.ModelEvalItemRepository;
import com.example.myllm.eval.repository.ModelEvalRunRepository;
import com.example.myllm.eval.support.EvalScoreSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 根据已评分题目刷新 run 汇总（总分、维度均分、完成数）。 */
@Service
public class ModelEvalSummaryRefresher {

    private final ModelEvalRunRepository runRepository;
    private final ModelEvalItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    public ModelEvalSummaryRefresher(
            ModelEvalRunRepository runRepository,
            ModelEvalItemRepository itemRepository,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refresh(String runId) {
        ModelEvalRun run = runRepository.findById(runId).orElseThrow();
        List<ModelEvalItem> items = itemRepository.findByRunIdOrderByItemIndexAsc(runId);
        List<ModelEvalItem> scored = items.stream()
                .filter(i -> i.getItemScore() != null)
                .toList();
        int done = (int) items.stream()
                .filter(i -> i.getStatus().name().equals("DONE") || i.getStatus().name().equals("FAILED"))
                .count();
        run.setCompletedQuestions(done);
        if (!scored.isEmpty()) {
            run.setTotalScore(EvalScoreSupport.aggregateTotalScore(scored));
            EvalDimensionScores dims = EvalScoreSupport.aggregateDimensions(scored);
            try {
                run.setDimensionScoresJson(objectMapper.writeValueAsString(dims));
            } catch (JsonProcessingException e) {
                run.setDimensionScoresJson(null);
            }
        } else {
            run.setTotalScore(null);
            run.setDimensionScoresJson(null);
        }
        runRepository.save(run);
    }
}
