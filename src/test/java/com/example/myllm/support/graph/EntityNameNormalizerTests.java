package com.example.myllm.support.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EntityNameNormalizerTests {

    @Test
    void normalizesWhitespaceAndUnicode() {
        assertEquals("贷记卡 呆账核销", EntityNameNormalizer.normalize("  贷记卡\n\n呆账核销  "));
    }

    @Test
    void buildsStableEntityKey() {
        String key = EntityNameNormalizer.entityKey(GraphEntityType.TERM, "临时额度");
        assertEquals("TERM:临时额度", key);
    }

    @Test
    void parsesEntityAndRelationTypes() {
        assertTrue(GraphEntityType.parse("product").isPresent());
        assertTrue(GraphRelationType.parse("APPLIES_TO").isPresent());
    }
}
