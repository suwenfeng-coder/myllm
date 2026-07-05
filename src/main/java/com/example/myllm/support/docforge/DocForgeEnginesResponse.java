package com.example.myllm.support.docforge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DocForgeEnginesResponse(List<DocForgeEngineInfo> engines) {
}
