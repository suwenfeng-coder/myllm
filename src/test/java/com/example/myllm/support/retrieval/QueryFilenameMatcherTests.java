package com.example.myllm.support.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueryFilenameMatcherTests {

    @Test
    void extractHintsFromBookTitleAndExtension() {
        String query = "查看《汽车用户手册（2023年版）》.docx";

        var hints = QueryFilenameMatcher.extractHints(query);

        assertTrue(hints.contains("汽车用户手册（2023年版）"));
        assertTrue(hints.stream().anyMatch(h -> h.endsWith(".docx")));
    }

    @Test
    void filenameBoostMatchesTargetDocument() {
        double boost = QueryFilenameMatcher.filenameBoost(
                "查看《汽车用户手册（2023年版）》.docx",
                "《汽车用户手册（2023年版）》.docx",
                0.12);

        assertEquals(0.12, boost, 0.0001);
    }

    @Test
    void filenameBoostDoesNotMatchDifferentYear() {
        double boost = QueryFilenameMatcher.filenameBoost(
                "查看《汽车用户手册（2023年版）》.docx",
                "《汽车用户手册（2024年版）》.docx",
                0.12);

        assertEquals(0.0, boost, 0.0001);
    }

    @Test
    void filenameBoostNormalizesFullWidthParentheses() {
        double boost = QueryFilenameMatcher.filenameBoost(
                "汽车用户手册(2023年版)",
                "汽车用户手册（2023年版）.docx",
                0.12);

        assertEquals(0.0, boost, 0.0001);
        assertEquals(
                QueryFilenameMatcher.normalize("汽车用户手册(2023年版)"),
                QueryFilenameMatcher.normalize("汽车用户手册（2023年版）"));
    }
}
