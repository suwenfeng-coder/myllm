package com.example.myllm.support.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BgeM3TokenEstimatorTests {

    @Test
    void estimatesChineseRoughlyOneTokenPerCharacter() {
        assertEquals(6, BgeM3TokenEstimator.estimate("汽车用户手册"));
    }

    @Test
    void estimatesEnglishWithFourCharactersPerToken() {
        assertEquals(3, BgeM3TokenEstimator.estimate("hello world"));
    }

    @Test
    void endIndexRespectsMaxTokens() {
        String text = "汽车用户手册胎压报警处理办法";
        int end = BgeM3TokenEstimator.endIndexForMaxTokens(text, 0, 4);
        assertTrue(end > 0);
        assertTrue(BgeM3TokenEstimator.estimate(text.substring(0, end)) <= 4);
    }
}
