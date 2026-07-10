package com.example.myllm.eval.service;

import com.example.myllm.eval.entity.EvalItemStatus;
import com.example.myllm.eval.entity.ModelEvalItem;
import com.example.myllm.eval.support.EvalScoreSupport;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 评测单题状态持久化（短事务，与 LLM 调用分离）。 */
@Service
public class ModelEvalItemWriter {

    private final ModelEvalItemTransactionService transactionService;

    public ModelEvalItemWriter(ModelEvalItemTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public Optional<Long> claimNextPendingItemId(String runId) {
        return transactionService.claimNextPendingItemId(runId);
    }

    public void markItemStatus(Long itemId, EvalItemStatus status) {
        transactionService.markItemStatus(itemId, status);
    }

    public void saveAnswerResult(Long itemId, ModelEvalLlmService.AnswerResult answer) {
        transactionService.saveAnswerResult(itemId, answer);
    }

    public void saveJudgeResult(Long itemId, EvalScoreSupport.EvalJudgeResult judge, long scoreDurationMs) {
        transactionService.saveJudgeResult(itemId, judge, scoreDurationMs);
    }

    public void markItemFailed(Long itemId, String message) {
        transactionService.markItemFailed(itemId, message);
    }

    public ModelEvalItem requireItem(Long itemId) {
        return transactionService.requireItem(itemId);
    }
}
