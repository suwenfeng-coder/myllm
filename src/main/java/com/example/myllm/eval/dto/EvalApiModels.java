package com.example.myllm.eval.dto;

import com.example.myllm.eval.entity.EvalCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 模型评测 API 请求/响应模型。 */
public final class EvalApiModels {

    private EvalApiModels() {
    }

    public record EvalSuiteCreateRequest(@NotBlank String name, String description) {}

    public record EvalSuiteUpdateRequest(String name, String description) {}

    public record EvalSuiteResponse(
            Long id,
            String name,
            String description,
            String sourceRunId,
            int questionCount,
            String createdAt,
            String updatedAt) {}

    public record EvalQuestionCreateRequest(
            @NotBlank String title,
            @NotBlank String prompt,
            @NotNull EvalCategory category,
            String referenceAnswer,
            String scoringRubric,
            String outputFormat,
            Integer sortOrder,
            Boolean enabled) {}

    public record EvalQuestionUpdateRequest(
            String title,
            String prompt,
            EvalCategory category,
            String referenceAnswer,
            String scoringRubric,
            String outputFormat,
            Integer sortOrder,
            Boolean enabled) {}

    public record EvalQuestionResponse(
            Long id,
            Long suiteId,
            int sortOrder,
            String title,
            String prompt,
            EvalCategory category,
            String referenceAnswer,
            String scoringRubric,
            String outputFormat,
            boolean enabled) {}

    public record EvalRunCreateRequest(@NotNull Long suiteId, String parentRunId, String notes) {}

    public record EvalRunResponse(
            String runId,
            Long suiteId,
            String suiteName,
            String parentRunId,
            String modelName,
            String judgeModelName,
            String status,
            int totalQuestions,
            int completedQuestions,
            Double totalScore,
            EvalDimensionScores dimensionScores,
            String notes,
            String errorMessage,
            String startedAt,
            String finishedAt,
            String createdAt) {}

    public record EvalDimensionScores(
            Double understanding,
            Double reasoning,
            Double code,
            Double domain,
            Double stability,
            Double instruction) {}

    public record EvalItemResponse(
            Long id,
            int itemIndex,
            Long questionId,
            String title,
            String prompt,
            EvalCategory category,
            String referenceAnswer,
            String outputFormat,
            String status,
            String modelAnswer,
            Long answerDurationMs,
            Long scoreDurationMs,
            Integer understanding,
            Integer reasoning,
            Integer code,
            Integer domain,
            Integer stability,
            Integer instruction,
            Double itemScore,
            String judgeSummary,
            boolean manuallyEdited,
            String errorMessage) {}

    public record EvalItemScoreUpdateRequest(
            Integer understanding,
            Integer reasoning,
            Integer code,
            Integer domain,
            Integer stability,
            Integer instruction,
            String judgeSummary) {}

    public record EvalContinueRequest(String newSuiteName, String description) {}

    public record EvalContinueResponse(
            Long suiteId, String suiteName, String sourceRunId, int questionCount) {}

    public record EvalRunReportResponse(EvalRunResponse run, List<EvalItemResponse> items) {}
}
