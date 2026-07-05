package com.example.myllm.support.document;

import java.util.List;
import java.util.Map;

public record DocumentParseResult(
        ParsedDocument document,
        String requestedParseMode,
        String parseMode,
        String parserEngine,
        Integer parsedPages,
        long parseDurationMs,
        List<String> attemptedModes,
        String fallbackReason) {

    public DocumentParseResult {
        requestedParseMode = requestedParseMode == null ? "auto" : requestedParseMode;
        parseMode = parseMode == null ? "local" : parseMode;
        parserEngine = parserEngine == null ? parseMode : parserEngine;
        attemptedModes = attemptedModes == null ? List.of(parseMode) : List.copyOf(attemptedModes);
    }

    public static DocumentParseResult from(
            ParsedDocument document,
            String requestedParseMode,
            String appliedParseMode,
            long parseDurationMs,
            List<String> attemptedModes,
            String fallbackReason) {
        String engine = stringMetadata(document.metadata(), "actualParserEngine");
        if (engine == null || engine.isBlank()) {
            engine = stringMetadata(document.metadata(), "docForgeEngine");
        }
        if (engine == null || engine.isBlank()) {
            engine = stringMetadata(document.metadata(), "parser");
        }
        if (engine == null || engine.isBlank()) {
            engine = appliedParseMode;
        }
        Integer pages = integerMetadata(document.metadata(), "parsePages");
        if (pages == null) {
            pages = integerMetadata(document.metadata(), "doclingPages");
        }
        String engineFallback = stringMetadata(document.metadata(), "engineFallbackReason");
        String effectiveFallbackReason = fallbackReason;
        if (engineFallback != null && !engineFallback.isBlank()) {
            effectiveFallbackReason = effectiveFallbackReason == null || effectiveFallbackReason.isBlank()
                    ? engineFallback
                    : effectiveFallbackReason + "; " + engineFallback;
        }
        return new DocumentParseResult(
                document,
                requestedParseMode,
                appliedParseMode,
                engine,
                pages,
                parseDurationMs,
                attemptedModes,
                effectiveFallbackReason);
    }

    private static String stringMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integerMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
