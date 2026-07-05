package com.example.myllm.eval.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myllm.eval.config.EvalProperties;
import com.example.myllm.eval.entity.EvalCategory;
import com.example.myllm.eval.entity.EvalRunStatus;
import com.example.myllm.eval.entity.ModelEvalItem;
import com.example.myllm.eval.entity.ModelEvalRun;
import com.example.myllm.eval.repository.ModelEvalItemRepository;
import com.example.myllm.eval.repository.ModelEvalRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelEvalRunServiceTests {

    @Test
    void processRunStopsWholeRunWhenAnswerModelIsUnavailable() {
        ModelEvalRunRepository runRepository = mock(ModelEvalRunRepository.class);
        ModelEvalItemRepository itemRepository = mock(ModelEvalItemRepository.class);
        ModelEvalSuiteService suiteService = mock(ModelEvalSuiteService.class);
        ModelEvalLlmService llmService = mock(ModelEvalLlmService.class);
        ModelEvalItemWriter itemWriter = mock(ModelEvalItemWriter.class);
        ModelEvalSummaryRefresher summaryRefresher = mock(ModelEvalSummaryRefresher.class);
        ModelEvalRunService service = new ModelEvalRunService(
                runRepository,
                itemRepository,
                suiteService,
                llmService,
                itemWriter,
                summaryRefresher,
                new EvalProperties(),
                new ObjectMapper());

        ModelEvalRun run = new ModelEvalRun();
        run.setStatus(EvalRunStatus.RUNNING);
        ModelEvalItem item = new ModelEvalItem();
        item.setId(7L);
        item.setItemIndex(1);
        item.setTitle("测试题");
        item.setPrompt("请回答");
        item.setCategory(EvalCategory.UNDERSTANDING);
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(itemWriter.claimNextPendingItemId("run-1")).thenReturn(Optional.of(7L));
        when(itemWriter.requireItem(7L)).thenReturn(item);
        when(llmService.answer(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("502 Bad Gateway"));

        ModelEvalRunService.ProcessRunResult result = service.processRun("run-1");

        assertTrue(result.fatal());
        verify(itemWriter).markItemFailed(org.mockito.ArgumentMatchers.eq(7L), contains("502 Bad Gateway"));
    }
}
