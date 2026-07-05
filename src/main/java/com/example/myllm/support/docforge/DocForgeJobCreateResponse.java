package com.example.myllm.support.docforge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DocForgeJobCreateResponse(
        @JsonProperty("job_id") String jobId,
        String status,
        String engine) {
}
