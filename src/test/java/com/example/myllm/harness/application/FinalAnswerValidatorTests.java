package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.config.HarnessProperties;
import com.example.myllm.harness.domain.HarnessAction;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 最终回答引用白名单测试。 */
class FinalAnswerValidatorTests {

    private final HarnessProperties properties = new HarnessProperties();
    private final FinalAnswerValidator validator = new FinalAnswerValidator(properties);

    @Test
    void acceptsCitationFromActualEvidence() {
        HarnessAction.Final answer = new HarnessAction.Final(
                "结论", List.of(new HarnessAction.Citation("file-1:7", "证据")), "完成");

        assertTrue(validator.validate(answer, Set.of("file-1:7")).valid());
    }

    @Test
    void rejectsMissingOrFabricatedCitationWhenEvidenceExists() {
        HarnessAction.Final missing = new HarnessAction.Final("结论", List.of(), "完成");
        HarnessAction.Final fabricated = new HarnessAction.Final(
                "结论", List.of(new HarnessAction.Citation("file-x:99", "伪造")), "完成");

        assertFalse(validator.validate(missing, Set.of("file-1:7")).valid());
        assertFalse(validator.validate(fabricated, Set.of("file-1:7")).valid());
    }

    @Test
    void directAnswerWithoutEvidenceDoesNotRequireCitation() {
        HarnessAction.Final answer = new HarnessAction.Final("你好", List.of(), "寒暄");

        assertTrue(validator.validate(answer, Set.of()).valid());
    }
}
