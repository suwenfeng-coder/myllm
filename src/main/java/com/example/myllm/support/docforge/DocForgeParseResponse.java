package com.example.myllm.support.docforge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DocForgeParseResponse(
        String status,
        String engine,
        String format,
        Object content,
        DocForgeParseMetadata metadata) {

    public String contentAsText() {
        if (content == null) {
            return "";
        }
        return content instanceof String text ? text : content.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocForgeParseMetadata(
            String filename,
            @JsonProperty("input_format") String inputFormat,
            Integer pages,
            @JsonProperty("duration_ms") long durationMs,
            String status,
            @JsonProperty("actual_engine") String actualEngine,
            @JsonProperty("fallback_reason") String fallbackReason) {
    }
}
