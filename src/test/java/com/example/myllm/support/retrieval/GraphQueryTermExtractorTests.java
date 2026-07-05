package com.example.myllm.support.retrieval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraphQueryTermExtractorTests {

    @Test
    void extractsFullQueryAndChinesePhrases() {
        List<String> terms = GraphQueryTermExtractor.extract("呆账核销的基本定义");
        assertTrue(terms.contains("呆账核销的基本定义"));
        assertTrue(terms.contains("呆账核销"));
        assertTrue(terms.contains("基本定义"));
    }

    @Test
    void buildsFulltextQueryWithOrJoin() {
        String query = GraphQueryTermExtractor.toFulltextQuery(
                GraphQueryTermExtractor.extract("呆账核销的基本定义"));
        assertTrue(query.contains("呆账核销"));
        assertTrue(query.contains(" OR "));
    }
}
