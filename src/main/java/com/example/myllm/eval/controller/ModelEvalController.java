package com.example.myllm.eval.controller;

import com.example.myllm.eval.dto.EvalApiModels;
import com.example.myllm.eval.dto.EvalApiModels.EvalContinueRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalContinueResponse;
import com.example.myllm.eval.dto.EvalApiModels.EvalItemResponse;
import com.example.myllm.eval.dto.EvalApiModels.EvalItemScoreUpdateRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalQuestionCreateRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalQuestionResponse;
import com.example.myllm.eval.dto.EvalApiModels.EvalQuestionUpdateRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalRunCreateRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalRunReportResponse;
import com.example.myllm.eval.dto.EvalApiModels.EvalRunResponse;
import com.example.myllm.eval.dto.EvalApiModels.EvalSuiteCreateRequest;
import com.example.myllm.eval.dto.EvalApiModels.EvalSuiteResponse;
import com.example.myllm.eval.dto.EvalApiModels.EvalSuiteUpdateRequest;
import com.example.myllm.eval.service.ModelEvalRunService;
import com.example.myllm.eval.service.ModelEvalSuiteService;
import com.example.myllm.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eval")
public class ModelEvalController {

    private final ModelEvalSuiteService suiteService;
    private final ModelEvalRunService runService;
    private final ChatService chatService;

    public ModelEvalController(
            ModelEvalSuiteService suiteService,
            ModelEvalRunService runService,
            ChatService chatService) {
        this.suiteService = suiteService;
        this.runService = runService;
        this.chatService = chatService;
    }

    @GetMapping("/model")
    public String currentModel() {
        return chatService.getModelInfo();
    }

    @GetMapping("/suites")
    public List<EvalSuiteResponse> listSuites() {
        return suiteService.listSuites();
    }

    @GetMapping("/suites/{suiteId}")
    public EvalSuiteResponse getSuite(@PathVariable Long suiteId) {
        return suiteService.getSuite(suiteId);
    }

    @PostMapping("/suites")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalSuiteResponse createSuite(@Valid @RequestBody EvalSuiteCreateRequest request) {
        return suiteService.createSuite(request);
    }

    @PutMapping("/suites/{suiteId}")
    public EvalSuiteResponse updateSuite(
            @PathVariable Long suiteId, @RequestBody EvalSuiteUpdateRequest request) {
        return suiteService.updateSuite(suiteId, request);
    }

    @DeleteMapping("/suites/{suiteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSuite(@PathVariable Long suiteId) {
        suiteService.deleteSuite(suiteId);
    }

    @PostMapping("/suites/import-default")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalSuiteResponse importDefault() {
        return suiteService.importDefaultBenchmark();
    }

    @GetMapping("/suites/{suiteId}/questions")
    public List<EvalQuestionResponse> listQuestions(@PathVariable Long suiteId) {
        return suiteService.listQuestions(suiteId);
    }

    @PostMapping("/suites/{suiteId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalQuestionResponse addQuestion(
            @PathVariable Long suiteId, @Valid @RequestBody EvalQuestionCreateRequest request) {
        return suiteService.addQuestion(suiteId, request);
    }

    @PutMapping("/questions/{questionId}")
    public EvalQuestionResponse updateQuestion(
            @PathVariable Long questionId, @RequestBody EvalQuestionUpdateRequest request) {
        return suiteService.updateQuestion(questionId, request);
    }

    @DeleteMapping("/questions/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQuestion(@PathVariable Long questionId) {
        suiteService.deleteQuestion(questionId);
    }

    @GetMapping("/runs")
    public List<EvalRunResponse> listRuns() {
        return runService.listRuns();
    }

    @GetMapping("/runs/{runId}")
    public EvalRunResponse getRun(@PathVariable String runId) {
        return runService.getRun(runId);
    }

    @GetMapping("/runs/{runId}/report")
    public EvalRunReportResponse getReport(@PathVariable String runId) {
        return runService.getReport(runId);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalRunResponse createRun(@Valid @RequestBody EvalRunCreateRequest request) {
        return runService.createRun(request);
    }

    @PostMapping("/runs/{runId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelRun(@PathVariable String runId) {
        runService.cancelRun(runId);
    }

    @PostMapping("/runs/{runId}/continue")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalContinueResponse continueFromRun(
            @PathVariable String runId, @RequestBody(required = false) EvalContinueRequest request) {
        return suiteService.cloneSuiteFromRun(runId, request);
    }

    @PutMapping("/items/{itemId}/scores")
    public EvalItemResponse updateItemScores(
            @PathVariable Long itemId, @RequestBody EvalItemScoreUpdateRequest request) {
        return runService.updateItemScores(itemId, request);
    }
}
