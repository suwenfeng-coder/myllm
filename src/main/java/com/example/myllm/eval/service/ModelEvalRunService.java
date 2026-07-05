package com.example.myllm.eval.service;

import com.example.myllm.eval.config.EvalProperties;
import com.example.myllm.eval.dto.EvalApiModels;
import com.example.myllm.eval.dto.EvalApiModels.EvalDimensionScores;
import com.example.myllm.eval.dto.EvalApiModels.EvalItemResponse;
import com.example.myllm.eval.dto.EvalApiModels.EvalItemScoreUpdateRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalRunCreateRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalRunReportResponse;
import com.example.myllm.eval.dto.EvalApiModels.EvalRunResponse;
import com.example.myllm.eval.entity.EvalItemStatus;
import com.example.myllm.eval.entity.EvalRunStatus;
import com.example.myllm.eval.entity.ModelEvalItem;
import com.example.myllm.eval.entity.ModelEvalQuestion;
import com.example.myllm.eval.entity.ModelEvalRun;
import com.example.myllm.eval.entity.ModelEvalSuite;
import com.example.myllm.eval.repository.ModelEvalItemRepository;
import com.example.myllm.eval.repository.ModelEvalRunRepository;
import com.example.myllm.eval.support.EvalScoreSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 评测运行创建、查询与分数汇总。 */
@Service
public class ModelEvalRunService {

    private static final Logger log = LoggerFactory.getLogger(ModelEvalRunService.class);

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ERROR = 4000;

    private final ModelEvalRunRepository runRepository;
    private final ModelEvalItemRepository itemRepository;
    private final ModelEvalSuiteService suiteService;
    private final ModelEvalLlmService llmService;
    private final ModelEvalItemWriter itemWriter;
    private final ModelEvalSummaryRefresher summaryRefresher;
    private final EvalProperties properties;
    private final ObjectMapper objectMapper;

    public ModelEvalRunService(
            ModelEvalRunRepository runRepository,
            ModelEvalItemRepository itemRepository,
            ModelEvalSuiteService suiteService,
            ModelEvalLlmService llmService,
            ModelEvalItemWriter itemWriter,
            ModelEvalSummaryRefresher summaryRefresher,
            EvalProperties properties,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.suiteService = suiteService;
        this.llmService = llmService;
        this.itemWriter = itemWriter;
        this.summaryRefresher = summaryRefresher;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<EvalRunResponse> listRuns() {
        return runRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .map(this::toRunResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EvalRunResponse getRun(String runId) {
        return toRunResponse(requireRun(runId));
    }

    @Transactional(readOnly = true)
    public EvalRunReportResponse getReport(String runId) {
        ModelEvalRun run = requireRun(runId);
        List<EvalItemResponse> items = itemRepository.findByRunIdOrderByItemIndexAsc(runId).stream()
                .map(this::toItemResponse)
                .toList();
        return new EvalRunReportResponse(toRunResponse(run), items);
    }

    @Transactional
    public EvalRunResponse createRun(EvalRunCreateRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("模型评测功能未启用");
        }
        ModelEvalSuite suite = suiteService.requireSuite(request.suiteId());
        List<ModelEvalQuestion> questions = suiteService.enabledQuestions(request.suiteId());
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("题库中没有启用的题目");
        }

        String runId = UUID.randomUUID().toString();
        String modelName = llmService.currentModelName();

        ModelEvalRun run = new ModelEvalRun();
        run.setRunId(runId);
        run.setSuiteId(suite.getId());
        run.setSuiteName(suite.getName());
        run.setParentRunId(trimToNull(request.parentRunId()));
        run.setModelName(modelName);
        run.setJudgeModelName(modelName);
        run.setStatus(EvalRunStatus.PENDING);
        run.setTotalQuestions(questions.size());
        run.setCompletedQuestions(0);
        run.setNotes(trimToNull(request.notes()));
        runRepository.save(run);

        int index = 1;
        for (ModelEvalQuestion q : questions) {
            ModelEvalItem item = new ModelEvalItem();
            item.setRunId(runId);
            item.setQuestionId(q.getId());
            item.setItemIndex(index++);
            item.setTitle(q.getTitle());
            item.setPrompt(q.getPrompt());
            item.setCategory(q.getCategory());
            item.setReferenceAnswer(q.getReferenceAnswer());
            item.setOutputFormat(q.getOutputFormat());
            item.setStatus(EvalItemStatus.PENDING);
            item.setManuallyEdited(false);
            itemRepository.save(item);
        }

        return toRunResponse(run);
    }

    @Transactional
    public void cancelRun(String runId) {
        ModelEvalRun run = requireRun(runId);
        if (run.getStatus() == EvalRunStatus.COMPLETED || run.getStatus() == EvalRunStatus.FAILED) {
            return;
        }
        run.setStatus(EvalRunStatus.CANCELLED);
        run.setFinishedAt(now());
        runRepository.save(run);
    }

    @Transactional
    public EvalItemResponse updateItemScores(Long itemId, EvalItemScoreUpdateRequest request) {
        ModelEvalItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("评测题目不存在: " + itemId));
        EvalScoreSupport.applyManualScores(
                item,
                request.understanding(),
                request.reasoning(),
                request.code(),
                request.domain(),
                request.stability(),
                request.instruction(),
                request.judgeSummary());
        itemRepository.save(item);
        recalculateRunSummary(item.getRunId());
        return toItemResponse(item);
    }

    @Transactional
    public Optional<ModelEvalRun> claimNextRunnableRun() {
        Optional<ModelEvalRun> locked = runRepository.lockNextRunnableRun();
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        ModelEvalRun run = locked.get();
        if (run.getStatus() == EvalRunStatus.PENDING) {
            run.setStatus(EvalRunStatus.RUNNING);
            run.setStartedAt(now());
            runRepository.save(run);
        }
        return Optional.of(run);
    }

    @Transactional
    public int recoverTimedOutRuns() {
        LocalDateTime cutoff = now().minusNanos(properties.getTask().getRunningTimeoutMs() * 1_000_000L);
        return runRepository.failTimedOutRunningRuns(cutoff);
    }

    /** 处理单题：LLM 调用在事务外执行，避免长事务回滚丢进度。 */
    public ProcessRunResult processRun(String runId) {
        ModelEvalRun run = requireRun(runId);
        if (run.getStatus() == EvalRunStatus.CANCELLED) {
            return ProcessRunResult.continueRun();
        }

        Optional<Long> itemIdOpt = itemWriter.claimNextPendingItemId(runId);
        if (itemIdOpt.isEmpty()) {
            return ProcessRunResult.continueRun();
        }
        Long itemId = itemIdOpt.get();
        ModelEvalItem item = itemWriter.requireItem(itemId);
        ModelEvalQuestion question = toQuestionSnapshot(item);

        itemWriter.markItemStatus(itemId, EvalItemStatus.ANSWERING);
        log.info("评测答题开始 runId={} item={} title={}", runId, item.getItemIndex(), item.getTitle());
        ModelEvalLlmService.AnswerResult answer;
        try {
            answer = llmService.answer(question);
            itemWriter.saveAnswerResult(itemId, answer);
        } catch (Exception e) {
            String error = "答题调用模型失败: " + rootCauseMessage(e);
            log.warn("评测答题失败并终止本轮 runId={} item={} error={}",
                    runId, item.getItemIndex(), error);
            itemWriter.markItemFailed(itemId, error);
            return ProcessRunResult.fatal(error);
        }

        try {
            log.info("评测裁判开始 runId={} item={} answerMs={}", runId, item.getItemIndex(), answer.durationMs());
            long scoreStart = System.nanoTime();
            EvalScoreSupport.EvalJudgeResult judge = llmService.judge(question, answer.answer());
            itemWriter.saveJudgeResult(itemId, judge, (System.nanoTime() - scoreStart) / 1_000_000L);

            log.info("评测单题完成 runId={} item={} score={}", runId, item.getItemIndex(),
                    itemWriter.requireItem(itemId).getItemScore());
        } catch (Exception e) {
            String error = "裁判调用失败: " + rootCauseMessage(e);
            log.warn("评测裁判失败 runId={} item={} error={}", runId, item.getItemIndex(), error);
            itemWriter.markItemFailed(itemId, error);
        }
        return ProcessRunResult.continueRun();
    }

    @Transactional
    public void failRun(String runId, String errorMessage) {
        ModelEvalRun run = requireRun(runId);
        if (run.getStatus() == EvalRunStatus.CANCELLED || run.getStatus() == EvalRunStatus.COMPLETED) {
            return;
        }
        run.setStatus(EvalRunStatus.FAILED);
        run.setErrorMessage(truncate(errorMessage));
        run.setFinishedAt(now());
        runRepository.save(run);
    }

    @Transactional(readOnly = true)
    public boolean hasPendingItems(String runId) {
        return itemRepository.findByRunIdOrderByItemIndexAsc(runId).stream()
                .anyMatch(i -> i.getStatus() == EvalItemStatus.PENDING
                        || i.getStatus() == EvalItemStatus.ANSWERING
                        || i.getStatus() == EvalItemStatus.SCORING);
    }

    @Transactional
    public void finalizeRun(String runId) {
        ModelEvalRun run = requireRun(runId);
        if (run.getStatus() == EvalRunStatus.CANCELLED) {
            return;
        }
        List<ModelEvalItem> items = itemRepository.findByRunIdOrderByItemIndexAsc(runId);
        long pending = items.stream().filter(i -> i.getStatus() == EvalItemStatus.PENDING).count();
        if (pending > 0) {
            return;
        }

        recalculateRunSummary(runId);
        run = requireRun(runId);
        long failed = items.stream().filter(i -> i.getStatus() == EvalItemStatus.FAILED).count();
        run.setStatus(failed == items.size() ? EvalRunStatus.FAILED : EvalRunStatus.COMPLETED);
        run.setFinishedAt(now());
        runRepository.save(run);
    }

    private void recalculateRunSummary(String runId) {
        summaryRefresher.refresh(runId);
    }

    private ModelEvalQuestion toQuestionSnapshot(ModelEvalItem item) {
        ModelEvalQuestion q = new ModelEvalQuestion();
        q.setTitle(item.getTitle());
        q.setPrompt(item.getPrompt());
        q.setCategory(item.getCategory());
        q.setReferenceAnswer(item.getReferenceAnswer());
        q.setOutputFormat(item.getOutputFormat());
        return q;
    }

    ModelEvalRun requireRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("评测记录不存在: " + runId));
    }

    private EvalRunResponse toRunResponse(ModelEvalRun run) {
        EvalDimensionScores dims = parseDimensions(run.getDimensionScoresJson());
        return new EvalRunResponse(
                run.getRunId(),
                run.getSuiteId(),
                run.getSuiteName(),
                run.getParentRunId(),
                run.getModelName(),
                run.getJudgeModelName(),
                run.getStatus().name(),
                run.getTotalQuestions(),
                run.getCompletedQuestions(),
                run.getTotalScore(),
                dims,
                run.getNotes(),
                run.getErrorMessage(),
                formatDt(run.getStartedAt()),
                formatDt(run.getFinishedAt()),
                formatDt(run.getCreatedAt()));
    }

    private EvalItemResponse toItemResponse(ModelEvalItem item) {
        return new EvalItemResponse(
                item.getId(),
                item.getItemIndex(),
                item.getQuestionId(),
                item.getTitle(),
                item.getPrompt(),
                item.getCategory(),
                item.getReferenceAnswer(),
                item.getOutputFormat(),
                item.getStatus().name(),
                item.getModelAnswer(),
                item.getAnswerDurationMs(),
                item.getScoreDurationMs(),
                item.getUnderstanding(),
                item.getReasoning(),
                item.getCode(),
                item.getDomain(),
                item.getStability(),
                item.getInstruction(),
                item.getItemScore(),
                item.getJudgeSummary(),
                Boolean.TRUE.equals(item.getManuallyEdited()),
                item.getErrorMessage());
    }

    private EvalDimensionScores parseDimensions(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, EvalDimensionScores.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.systemDefault());
    }

    private static String formatDt(LocalDateTime dt) {
        return dt == null ? null : dt.format(DT);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR ? message : message.substring(0, MAX_ERROR);
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return truncate(message);
    }

    public record ProcessRunResult(boolean fatal, String errorMessage) {
        static ProcessRunResult continueRun() {
            return new ProcessRunResult(false, null);
        }

        static ProcessRunResult fatal(String errorMessage) {
            return new ProcessRunResult(true, errorMessage);
        }
    }
}
