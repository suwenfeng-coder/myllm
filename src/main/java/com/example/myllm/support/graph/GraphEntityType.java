package com.example.myllm.support.graph;

import java.util.Locale;
import java.util.Optional;

/** 受控实体类型白名单，用于 LLM 抽取结果校验。 */
public enum GraphEntityType {
    ORGANIZATION,
    PRODUCT,
    CARD,
    ACCOUNT,
    POLICY,
    PROCESS,
    ROLE,
    TERM,
    TABLE,
    FIELD,
    SYSTEM;

    public static Optional<GraphEntityType> parse(String value) {
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
