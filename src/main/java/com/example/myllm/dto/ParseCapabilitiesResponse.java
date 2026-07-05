package com.example.myllm.dto;

import java.util.List;

public record ParseCapabilitiesResponse(
        List<String> localExtensions,
        boolean docforgeEnabled,
        boolean docforgeReady,
        List<RemoteEngineCapability> engines,
        List<String> remoteExtensions,
        int syncMaxSizeMb,
        int asyncMaxSizeMb) {

    public record RemoteEngineCapability(
            String name,
            String engine,
            boolean defaultEngine,
            boolean available,
            String unavailableReason,
            List<String> extensions,
            List<String> outputFormats) {
    }
}
