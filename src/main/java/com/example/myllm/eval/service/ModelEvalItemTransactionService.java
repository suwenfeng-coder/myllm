package com.example.myllm.eval.service;

import com.example.myllm.eval.entity.EvalItemStatus;
import com.example.myllm.eval.entity.ModelEvalItem;
import com.example.myllm.eval.repository.ModelEvalItemRepository;
import com.example.myllm.eval.support.EvalScoreSupport;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional model-eval item mutations. Invoked through a Spring proxy. */
@Service
public class ModelEvalItemTransactionService {

    private static final int MAX_ERROR = 4000;

    private final ModelEvalItemRepository itemRepository;
    private final ModelEvalSummaryRefresher summaryRefresher;

    public ModelEvalItemTransactionService(
            ModelEvalItemRepository itemRepository,
            ModelEvalSummaryRefresher summaryRefresher) {
        this.itemRepository = itemRepository;
        this.summaryRefresher = summaryRefresher;
    }

    @Transactional
    public Optional<Long> claimNextPendingItemId(String runId) {
        resetInProgressItems(runId);
        return itemRepository.findFirstByRunIdAndStatusOrderByItemIndexAsc(runId, EvalItemStatus.PENDING)
                .map(ModelEvalItem::getId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markItemStatus(Long itemId, EvalItemStatus status) {
        ModelEvalItem item = requireItem(itemId);
        item.setStatus(status);
        itemRepository.save(item);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAnswerResult(Long itemId, ModelEvalLlmService.AnswerResult answer) {
        ModelEvalItem item = requireItem(itemId);
        item.setModelAnswer(answer.answer());
        item.setAnswerDurationMs(answer.durationMs());
        item.setStatus(EvalItemStatus.SCORING);
        itemRepository.save(item);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveJudgeResult(Long itemId, EvalScoreSupport.EvalJudgeResult judge, long scoreDurationMs) {
        ModelEvalItem item = requireItem(itemId);
        EvalScoreSupport.applyScores(item, judge);
        item.setScoreDurationMs(scoreDurationMs);
        item.setStatus(EvalItemStatus.DONE);
        itemRepository.save(item);
        summaryRefresher.refresh(item.getRunId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markItemFailed(Long itemId, String message) {
        ModelEvalItem item = requireItem(itemId);
        item.setStatus(EvalItemStatus.FAILED);
        item.setErrorMessage(truncate(message));
        itemRepository.save(item);
        summaryRefresher.refresh(item.getRunId());
    }

    public ModelEvalItem requireItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("评测题目不存在: " + itemId));
    }

    private void resetInProgressItems(String runId) {
        itemRepository.findByRunIdOrderByItemIndexAsc(runId).stream()
                .filter(i -> i.getStatus() == EvalItemStatus.ANSWERING || i.getStatus() == EvalItemStatus.SCORING)
                .forEach(i -> {
                    i.setStatus(EvalItemStatus.PENDING);
                    itemRepository.save(i);
                });
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR ? message : message.substring(0, MAX_ERROR);
    }
}
