package com.example.myllm.support.docforge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DocForgeEngineInfo(
        String name,
        @JsonProperty("default") boolean defaultEngine,
        @JsonProperty("input_extensions") List<String> inputExtensions,
        @JsonProperty("output_formats") List<String> outputFormats,
        Boolean enabled,
        Boolean installed,
        Boolean ready,
        @JsonProperty("unavailable_reason") String unavailableReason) {

    /**
     * 兼容旧版 DocForge：旧响应没有状态字段时，已列出的引擎视为可选。
     */
    public boolean available() {
        return !Boolean.FALSE.equals(enabled)
                && !Boolean.FALSE.equals(installed)
                && !Boolean.FALSE.equals(ready);
    }
}
