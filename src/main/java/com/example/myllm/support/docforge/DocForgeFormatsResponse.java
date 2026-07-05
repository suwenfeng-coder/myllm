package com.example.myllm.support.docforge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DocForgeFormatsResponse(
        String engine,
        @JsonProperty("input_extensions") List<String> inputExtensions,
        @JsonProperty("output_formats") List<String> outputFormats,
        @JsonProperty("max_file_size_mb") int maxFileSizeMb,
        @JsonProperty("sync_max_file_size_mb") int syncMaxFileSizeMb) {
}
