package com.example.myllm.dto;

import java.util.List;

public record ChatResponse(
        String reply,
        String provider,
        List<RagSource> sources
) {
    public ChatResponse(String reply, String provider) {
        this(reply, provider, List.of());
    }
}
