package com.example.myllm.support.retrieval;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QueryPreprocessor {

    private static final Pattern POLITE_PREFIX = Pattern.compile("^(请问|请帮我|帮我|麻烦|麻烦你|帮忙|请)\\s*");
    private static final Pattern POLITE_SUFFIX = Pattern.compile("\\s*(谢谢|谢谢你|感谢|辛苦了)$");

    public ProcessedQuery preprocess(String rawQuery) {
        if (rawQuery == null) {
            return new ProcessedQuery("", List.of());
        }
        String normalized = normalize(rawQuery);
        List<String> hints = QueryFilenameMatcher.extractHints(normalized);
        return new ProcessedQuery(normalized, hints);
    }

    private static String normalize(String query) {
        String value = query.strip()
                .replace('，', ',')
                .replace('。', '.')
                .replace('？', '?')
                .replace('！', '!')
                .replace('：', ':')
                .replace('（', '(')
                .replace('）', ')');
        value = POLITE_PREFIX.matcher(value).replaceFirst("");
        value = POLITE_SUFFIX.matcher(value).replaceFirst("");
        return value.replaceAll("\\s+", " ").strip();
    }

    public record ProcessedQuery(String normalizedQuery, List<String> filenameHints) {
    }
}
