package com.example.myllm.eval.support;

import com.example.myllm.eval.dto.EvalApiModels.EvalItemScoreUpdateRequest;
import com.example.myllm.eval.entity.ModelEvalItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvalScoreSupportTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseJudgeJson_extractsSixDimensions() {
        String json = """
                {
                  "understanding": 4,
                  "reasoning": 3,
                  "code": 5,
                  "domain": 4,
                  "stability": 5,
                  "instruction": 4,
                  "summary": "整体良好"
                }
                """;
        EvalScoreSupport.EvalJudgeResult result = EvalScoreSupport.parseJudgeJson(objectMapper, json);
        assertEquals(4, result.understanding());
        assertEquals(5, result.code());
        assertEquals("整体良好", result.summary());
    }

    @Test
    void averageItemScore_computesMeanOfSixDimensions() {
        ModelEvalItem item = new ModelEvalItem();
        item.setUnderstanding(4);
        item.setReasoning(4);
        item.setCode(5);
        item.setDomain(3);
        item.setStability(4);
        item.setInstruction(4);
        assertEquals(4.0, EvalScoreSupport.averageItemScore(item));
    }

    @Test
    void applyManualScores_recalculatesItemScore() {
        ModelEvalItem item = new ModelEvalItem();
        item.setUnderstanding(2);
        item.setReasoning(2);
        item.setCode(2);
        item.setDomain(2);
        item.setStability(2);
        item.setInstruction(2);
        EvalScoreSupport.applyManualScores(
                item,
                new EvalItemScoreUpdateRequest(
                        null, null, 5, null, null, null, "人工修正代码分"));
        assertEquals(5, item.getCode());
        assertEquals(2.5, item.getItemScore());
    }
}
