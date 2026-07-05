package com.example.myllm.support.docforge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DocForgeJobProgress(
        int percent,
        String phase,
        String message,
        @JsonProperty("current_page") Integer currentPage,
        @JsonProperty("total_pages") Integer totalPages,
        @JsonProperty("eta_seconds") Integer etaSeconds) {
}
