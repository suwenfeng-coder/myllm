package com.example.myllm.eval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myllm.eval.entity.EvalItemStatus;
import com.example.myllm.eval.entity.ModelEvalItem;
import com.example.myllm.eval.entity.ModelEvalRun;
import com.example.myllm.eval.repository.ModelEvalItemRepository;
import com.example.myllm.eval.repository.ModelEvalRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelEvalSummaryRefresherTests {

    private final ModelEvalRunRepository runRepository = mock(ModelEvalRunRepository.class);
    private final ModelEvalItemRepository itemRepository = mock(ModelEvalItemRepository.class);
    private final ModelEvalSummaryRefresher refresher =
            new ModelEvalSummaryRefresher(runRepository, itemRepository, new ObjectMapper());

    @Test
    void refreshPublishesScoreAsSoonAsOneItemIsScored() {
        ModelEvalRun run = new ModelEvalRun();
        ModelEvalItem done = item(EvalItemStatus.DONE, 4.2);
        ModelEvalItem pending = item(EvalItemStatus.PENDING, null);
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(itemRepository.findByRunIdOrderByItemIndexAsc("run-1"))
                .thenReturn(List.of(done, pending));

        refresher.refresh("run-1");

        assertEquals(1, run.getCompletedQuestions());
        assertEquals(4.2, run.getTotalScore());
        verify(runRepository).save(run);
    }

    @Test
    void refreshDoesNotPresentInfrastructureFailureAsZeroScore() {
        ModelEvalRun run = new ModelEvalRun();
        run.setTotalScore(0.0);
        ModelEvalItem failed = item(EvalItemStatus.FAILED, null);
        when(runRepository.findById("run-2")).thenReturn(Optional.of(run));
        when(itemRepository.findByRunIdOrderByItemIndexAsc("run-2"))
                .thenReturn(List.of(failed));

        refresher.refresh("run-2");

        assertEquals(1, run.getCompletedQuestions());
        assertNull(run.getTotalScore());
        assertNull(run.getDimensionScoresJson());
    }

    private static ModelEvalItem item(EvalItemStatus status, Double score) {
        ModelEvalItem item = new ModelEvalItem();
        item.setStatus(status);
        item.setItemScore(score);
        if (score != null) {
            int rounded = (int) Math.round(score);
            item.setUnderstanding(rounded);
            item.setReasoning(rounded);
            item.setCode(rounded);
            item.setDomain(rounded);
            item.setStability(rounded);
            item.setInstruction(rounded);
        }
        return item;
    }
}
