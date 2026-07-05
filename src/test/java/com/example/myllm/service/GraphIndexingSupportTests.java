package com.example.myllm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GraphIndexingSupportTests {

    @Test
    void stableIdsAreScopedByFileAndPosition() {
        assertEquals("file-a:3", GraphIndexingService.chunkId("file-a", 3));
        assertEquals(
                GraphIndexingService.sectionId("file-a", "制度 > 额度"),
                GraphIndexingService.sectionId("file-a", "制度 > 额度"));
        assertNotEquals(
                GraphIndexingService.sectionId("file-a", "制度 > 额度"),
                GraphIndexingService.sectionId("file-b", "制度 > 额度"));
    }

    @Test
    void previewNormalizesWhitespaceAndLimitsUnicodeCodePoints() {
        assertEquals("第一行 第二行", GraphIndexingService.preview(" 第一行\n\n  第二行 "));

        String preview = GraphIndexingService.preview("你".repeat(350));
        assertEquals(300, preview.codePointCount(0, preview.length()));
    }

    @Test
    void headingIsNormalizedAndLevelIsBounded() {
        assertEquals("制度 > 额度 / 临时额度", GraphIndexingService.normalizeHeading(" 制度  >  额度 / 临时额度 "));
        assertEquals(3, GraphIndexingService.headingLevel("制度 > 额度 / 临时额度"));
        assertTrue(GraphIndexingService.headingLevel("制度") >= 1);
    }
}
