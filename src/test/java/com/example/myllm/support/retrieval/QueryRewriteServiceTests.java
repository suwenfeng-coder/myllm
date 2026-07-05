package com.example.myllm.support.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueryRewriteServiceTests {

    private final QueryRewriteService service = new QueryRewriteService(
            new QueryPreprocessor(),
            new QueryIntentClassifier(),
            true,
            true);

    @Test
    void filenameLookupUsesHintsAsRetrievalQuery() {
        QueryRewriteResult result = service.rewrite("请帮我查看《汽车用户手册（2025年版）》.docx，谢谢");

        assertEquals(QueryIntent.FILENAME_LOOKUP, result.intent());
        assertEquals(QueryRewriteStrategy.RULE_FILENAME_HINT, result.strategy());
        assertTrue(result.retrievalQuery().contains("汽车用户手册"));
        assertFalse(result.skipRag());
    }

    @Test
    void chitchatCanSkipRag() {
        QueryRewriteResult result = service.rewrite("谢谢");

        assertEquals(QueryIntent.SKIP_RAG, result.intent());
        assertTrue(result.skipRag());
    }

    @Test
    void hybridKeepsContentQuestion() {
        QueryRewriteResult result = service.rewrite("查看《汽车用户手册（2025年版）》.docx里胎压报警怎么处理？");

        assertEquals(QueryIntent.HYBRID, result.intent());
        assertEquals(QueryRewriteStrategy.RULE_NORMALIZED, result.strategy());
        assertTrue(result.retrievalQuery().contains("胎压报警"));
    }

    @Test
    void viewContentQuestionWithoutFileHintIsNotMisclassifiedAsFilenameLookup() {
        QueryRewriteResult result = service.rewrite("查看胎压报警怎么处理？");

        assertEquals(QueryIntent.CONTENT_QA, result.intent());
        assertEquals(QueryRewriteStrategy.RULE_NORMALIZED, result.strategy());
    }
}
