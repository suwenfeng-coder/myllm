package com.example.myllm.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GraphPropertiesTests {

    @Test
    void graphCapabilitiesAreDisabledByDefault() {
        GraphProperties properties = new GraphProperties();

        assertFalse(properties.isEnabled());
        assertFalse(properties.getIndexing().isEnabled());
        assertFalse(properties.getRetrieval().isEnabled());
        assertTrue(properties.getRetrieval().isShadowMode());
        assertEquals("neo4j", properties.getDatabase());
    }

    @Test
    void unsafeBoundariesAreClamped() {
        GraphProperties properties = new GraphProperties();
        properties.getIndexing().setBatchSize(0);
        properties.getIndexing().setMaxAttempts(0);
        properties.getIndexing().setMinConfidence(2.0);
        properties.getRetrieval().setMaxHops(100);
        properties.getRetrieval().setMinScore(-1.0);

        assertEquals(1, properties.getIndexing().getBatchSize());
        assertEquals(1, properties.getIndexing().getMaxAttempts());
        assertEquals(1.0, properties.getIndexing().getMinConfidence());
        assertEquals(2, properties.getRetrieval().getMaxHops());
        assertEquals(0.0, properties.getRetrieval().getMinScore());
    }
}
