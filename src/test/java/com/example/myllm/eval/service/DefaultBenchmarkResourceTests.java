package com.example.myllm.eval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DefaultBenchmarkResourceTests {

    @Test
    void defaultBenchmarkContainsIntelligenceEvaluationQuestions() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/eval/default-benchmark.json")) {
            Map<String, Object> root = objectMapper.readValue(in, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions = (List<Map<String, Object>>) root.get("questions");

            Set<String> titles = questions.stream()
                    .map(q -> (String) q.get("title"))
                    .collect(Collectors.toSet());

            assertEquals(28, questions.size());
            assertEquals(28, titles.size());
            assertTrue(titles.contains("会议排期推理"));
            assertTrue(titles.contains("信息不足时拒答"));
            assertTrue(titles.contains("幂等转账接口设计"));
            assertTrue(titles.contains("多条件产品推荐"));
            assertTrue(titles.contains("中文隐含意图识别"));
        }
    }
}
