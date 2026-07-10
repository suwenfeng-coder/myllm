package com.example.myllm.harness.application;

import com.example.myllm.harness.config.HarnessProperties;
import com.example.myllm.harness.domain.HarnessAction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 最终回答机械校验：长度、引用存在性、引用白名单和重复引用。 */
@Component
public class FinalAnswerValidator {

    private final HarnessProperties properties;

    public FinalAnswerValidator(HarnessProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验模型最终答案是否满足可投递条件。
     *
     * <p>这里做的是机械校验，不判断答案语义是否“聪明”：只检查答案非空、长度上限、引用是否存在、
     * 引用是否来自本轮真实命中的 sourceId、以及是否重复引用。这样即使模型尝试编造 citation，也会被
     * Repair 流程拦截。</p>
     */
    public ValidationResult validate(HarnessAction.Final answer, Set<String> allowedSourceIds) {
        List<String> issues = new ArrayList<>();
        Set<String> allowed = allowedSourceIds == null ? Set.of() : Set.copyOf(allowedSourceIds);
        if (answer == null || answer.answer() == null || answer.answer().isBlank()) {
            issues.add("最终答案为空");
        } else if (answer.answer().length() > properties.getVerification().getMaxAnswerChars()) {
            issues.add("最终答案超过最大字符数 " + properties.getVerification().getMaxAnswerChars());
        }

        List<HarnessAction.Citation> citations = answer == null ? List.of() : answer.citations();
        if (!allowed.isEmpty()
                && properties.getVerification().isRequireCitationsWhenEvidencePresent()
                && citations.isEmpty()) {
            issues.add("已有检索证据，但最终答案没有引用");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (HarnessAction.Citation citation : citations) {
            String sourceId = citation.sourceId() == null ? "" : citation.sourceId().trim();
            if (!allowed.contains(sourceId)) {
                issues.add("引用不在本次实际命中白名单中: " + sourceId);
            }
            if (!seen.add(sourceId)) {
                issues.add("存在重复引用: " + sourceId);
            }
        }
        return issues.isEmpty()
                ? ValidationResult.success()
                : ValidationResult.failure(issues);
    }

    public record ValidationResult(boolean valid, boolean repairable, List<String> issues) {

        static ValidationResult success() {
            return new ValidationResult(true, false, List.of());
        }

        static ValidationResult failure(List<String> issues) {
            return new ValidationResult(false, true, List.copyOf(issues));
        }

        public String repairFeedback() {
            return String.join("；", issues);
        }
    }
}
