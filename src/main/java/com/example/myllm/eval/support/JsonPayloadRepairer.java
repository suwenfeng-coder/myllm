package com.example.myllm.eval.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;

/** LLM 裁判 JSON 容错解析。 */
final class JsonPayloadRepairer {

    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

    private JsonPayloadRepairer() {
    }

    static JsonNode parseLenient(ObjectMapper objectMapper, String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("裁判返回空响应");
        }
        IllegalStateException lastError = null;
        for (String candidate : candidates(response)) {
            try {
                return objectMapper.readTree(candidate);
            } catch (Exception e) {
                lastError = new IllegalStateException("裁判 JSON 解析失败: " + e.getMessage(), e);
            }
        }
        throw lastError == null ? new IllegalStateException("裁判 JSON 解析失败") : lastError;
    }

    private static String[] candidates(String response) {
        String trimmed = response.strip();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new String[] {trimmed};
        }
        String extracted = trimmed.substring(start, end + 1);
        String repaired = TRAILING_COMMA.matcher(extracted).replaceAll("$1");
        if (repaired.equals(extracted)) {
            return new String[] {extracted};
        }
        return new String[] {extracted, repaired};
    }
}
