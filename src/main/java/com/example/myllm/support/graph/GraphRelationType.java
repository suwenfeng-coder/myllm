package com.example.myllm.support.graph;

import java.util.Locale;
import java.util.Optional;

/** 受控关系类型白名单，写入 Neo4j 的 relationType 属性。 */
public enum GraphRelationType {
    BELONGS_TO,
    APPLIES_TO,
    REQUIRES,
    PROHIBITS,
    DEPENDS_ON,
    USES,
    CONTAINS,
    MAPS_TO,
    PRECEDES;

    public static Optional<GraphRelationType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
