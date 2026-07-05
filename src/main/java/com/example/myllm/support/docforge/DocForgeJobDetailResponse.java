package com.example.myllm.support.docforge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DocForgeJobDetailResponse(
        @JsonProperty("job_id") String jobId,
        String status,
        String engine,
        String filename,
        @JsonProperty("output_format") String outputFormat,
        String error,
        DocForgeParseResponse.DocForgeParseMetadata metadata,
        DocForgeJobProgress progress,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}
