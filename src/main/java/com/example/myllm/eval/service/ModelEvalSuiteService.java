package com.example.myllm.eval.service;

import com.example.myllm.eval.dto.EvalApiModels;
import com.example.myllm.eval.dto.EvalApiModels.EvalQuestionCreateRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalQuestionResponse;
import com.example.myllm.eval.dto.EvalApiModels.EvalQuestionUpdateRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalSuiteCreateRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalSuiteResponse;
import com.example.myllm.eval.dto.EvalApiModels.EvalSuiteUpdateRequest;
import com.example.myllm.eval.entity.EvalCategory;
import com.example.myllm.eval.entity.ModelEvalItem;
import com.example.myllm.eval.entity.ModelEvalQuestion;
import com.example.myllm.eval.entity.ModelEvalRun;
import com.example.myllm.eval.entity.ModelEvalSuite;
import com.example.myllm.eval.repository.ModelEvalItemRepository;
import com.example.myllm.eval.repository.ModelEvalQuestionRepository;
import com.example.myllm.eval.repository.ModelEvalRunRepository;
import com.example.myllm.eval.repository.ModelEvalSuiteRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 评测题库与题目管理。 */
@Service
public class ModelEvalSuiteService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ModelEvalSuiteRepository suiteRepository;
    private final ModelEvalQuestionRepository questionRepository;
    private final ModelEvalRunRepository runRepository;
    private final ModelEvalItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    public ModelEvalSuiteService(
            ModelEvalSuiteRepository suiteRepository,
            ModelEvalQuestionRepository questionRepository,
            ModelEvalRunRepository runRepository,
            ModelEvalItemRepository itemRepository,
            ObjectMapper objectMapper) {
        this.suiteRepository = suiteRepository;
        this.questionRepository = questionRepository;
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<EvalSuiteResponse> listSuites() {
        return suiteRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toSuiteResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EvalSuiteResponse getSuite(Long suiteId) {
        return toSuiteResponse(requireSuite(suiteId));
    }

    @Transactional
    public EvalSuiteResponse createSuite(EvalSuiteCreateRequest request) {
        ModelEvalSuite suite = new ModelEvalSuite();
        suite.setName(request.name().trim());
        suite.setDescription(trimToNull(request.description()));
        return toSuiteResponse(suiteRepository.save(suite));
    }

    @Transactional
    public EvalSuiteResponse updateSuite(Long suiteId, EvalSuiteUpdateRequest request) {
        ModelEvalSuite suite = requireSuite(suiteId);
        if (request.name() != null && !request.name().isBlank()) {
            suite.setName(request.name().trim());
        }
        if (request.description() != null) {
            suite.setDescription(trimToNull(request.description()));
        }
        return toSuiteResponse(suiteRepository.save(suite));
    }

    @Transactional
    public void deleteSuite(Long suiteId) {
        questionRepository.deleteBySuiteId(suiteId);
        suiteRepository.delete(requireSuite(suiteId));
    }

    @Transactional(readOnly = true)
    public List<EvalQuestionResponse> listQuestions(Long suiteId) {
        requireSuite(suiteId);
        return questionRepository.findBySuiteIdOrderBySortOrderAscIdAsc(suiteId).stream()
                .map(this::toQuestionResponse)
                .toList();
    }

    @Transactional
    public EvalQuestionResponse addQuestion(Long suiteId, EvalQuestionCreateRequest request) {
        requireSuite(suiteId);
        ModelEvalQuestion question = new ModelEvalQuestion();
        question.setSuiteId(suiteId);
        question.setTitle(request.title().trim());
        question.setPrompt(request.prompt().trim());
        question.setCategory(request.category());
        question.setReferenceAnswer(trimToNull(request.referenceAnswer()));
        question.setScoringRubric(trimToNull(request.scoringRubric()));
        question.setOutputFormat(trimToNull(request.outputFormat()));
        question.setSortOrder(resolveSortOrder(suiteId, request.sortOrder()));
        question.setEnabled(request.enabled() == null || request.enabled());
        return toQuestionResponse(questionRepository.save(question));
    }

    @Transactional
    public EvalQuestionResponse updateQuestion(Long questionId, EvalQuestionUpdateRequest request) {
        ModelEvalQuestion question = requireQuestion(questionId);
        if (request.title() != null && !request.title().isBlank()) {
            question.setTitle(request.title().trim());
        }
        if (request.prompt() != null && !request.prompt().isBlank()) {
            question.setPrompt(request.prompt().trim());
        }
        if (request.category() != null) {
            question.setCategory(request.category());
        }
        if (request.referenceAnswer() != null) {
            question.setReferenceAnswer(trimToNull(request.referenceAnswer()));
        }
        if (request.scoringRubric() != null) {
            question.setScoringRubric(trimToNull(request.scoringRubric()));
        }
        if (request.outputFormat() != null) {
            question.setOutputFormat(trimToNull(request.outputFormat()));
        }
        if (request.sortOrder() != null) {
            question.setSortOrder(request.sortOrder());
        }
        if (request.enabled() != null) {
            question.setEnabled(request.enabled());
        }
        return toQuestionResponse(questionRepository.save(question));
    }

    @Transactional
    public void deleteQuestion(Long questionId) {
        questionRepository.delete(requireQuestion(questionId));
    }

    @Transactional
    public EvalSuiteResponse importDefaultBenchmark() {
        try (InputStream in = new ClassPathResource("eval/default-benchmark.json").getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(in, new TypeReference<>() {});
            String name = (String) root.getOrDefault("name", "银行后端基础卷");
            String description = (String) root.get("description");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions = (List<Map<String, Object>>) root.get("questions");

            ModelEvalSuite suite = new ModelEvalSuite();
            suite.setName(name);
            suite.setDescription(description);
            suite = suiteRepository.save(suite);

            int order = 1;
            for (Map<String, Object> q : questions) {
                ModelEvalQuestion question = new ModelEvalQuestion();
                question.setSuiteId(suite.getId());
                question.setSortOrder(order++);
                question.setTitle((String) q.get("title"));
                question.setPrompt((String) q.get("prompt"));
                question.setCategory(EvalCategory.valueOf((String) q.get("category")));
                question.setReferenceAnswer((String) q.get("referenceAnswer"));
                question.setScoringRubric((String) q.get("scoringRubric"));
                question.setOutputFormat((String) q.get("outputFormat"));
                question.setEnabled(true);
                questionRepository.save(question);
            }
            return toSuiteResponse(suite);
        } catch (IOException e) {
            throw new IllegalStateException("加载内置样卷失败: " + e.getMessage(), e);
        }
    }

    /** 基于历史评测复制题库，供修改后继续下一轮测试。 */
    @Transactional
    public EvalApiModels.EvalContinueResponse cloneSuiteFromRun(String runId, EvalApiModels.EvalContinueRequest request) {
        ModelEvalRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("评测记录不存在: " + runId));
        List<ModelEvalItem> items = itemRepository.findByRunIdOrderByItemIndexAsc(runId);
        if (items.isEmpty()) {
            throw new IllegalStateException("该评测无题目快照，无法复制");
        }

        String suiteName = request != null && request.newSuiteName() != null && !request.newSuiteName().isBlank()
                ? request.newSuiteName().trim()
                : run.getSuiteName() + " (续测 " + runId.substring(0, 8) + ")";

        ModelEvalSuite suite = new ModelEvalSuite();
        suite.setName(suiteName);
        suite.setDescription(request != null ? trimToNull(request.description()) : null);
        suite.setSourceRunId(runId);
        suite = suiteRepository.save(suite);

        int order = 1;
        for (ModelEvalItem item : items) {
            ModelEvalQuestion question = new ModelEvalQuestion();
            question.setSuiteId(suite.getId());
            question.setSortOrder(order++);
            question.setTitle(item.getTitle());
            question.setPrompt(item.getPrompt());
            question.setCategory(item.getCategory());
            question.setReferenceAnswer(item.getReferenceAnswer());
            question.setOutputFormat(item.getOutputFormat());
            question.setEnabled(true);
            questionRepository.save(question);
        }

        return new EvalApiModels.EvalContinueResponse(
                suite.getId(), suite.getName(), runId, items.size());
    }

    ModelEvalQuestion requireQuestion(Long questionId) {
        return questionRepository.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("题目不存在: " + questionId));
    }

    List<ModelEvalQuestion> enabledQuestions(Long suiteId) {
        return questionRepository.findBySuiteIdAndEnabledTrueOrderBySortOrderAscIdAsc(suiteId);
    }

    ModelEvalSuite requireSuite(Long suiteId) {
        return suiteRepository.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("题库不存在: " + suiteId));
    }

    private int resolveSortOrder(Long suiteId, Integer requested) {
        if (requested != null) {
            return requested;
        }
        List<ModelEvalQuestion> existing =
                questionRepository.findBySuiteIdOrderBySortOrderAscIdAsc(suiteId);
        return existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSortOrder() + 1;
    }

    private EvalSuiteResponse toSuiteResponse(ModelEvalSuite suite) {
        int count = questionRepository.findBySuiteIdOrderBySortOrderAscIdAsc(suite.getId()).size();
        return new EvalSuiteResponse(
                suite.getId(),
                suite.getName(),
                suite.getDescription(),
                suite.getSourceRunId(),
                count,
                formatDt(suite.getCreatedAt()),
                formatDt(suite.getUpdatedAt()));
    }

    private EvalQuestionResponse toQuestionResponse(ModelEvalQuestion q) {
        return new EvalQuestionResponse(
                q.getId(),
                q.getSuiteId(),
                q.getSortOrder(),
                q.getTitle(),
                q.getPrompt(),
                q.getCategory(),
                q.getReferenceAnswer(),
                q.getScoringRubric(),
                q.getOutputFormat(),
                Boolean.TRUE.equals(q.getEnabled()));
    }

    private static String formatDt(java.time.LocalDateTime dt) {
        return dt == null ? null : dt.format(DT);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
