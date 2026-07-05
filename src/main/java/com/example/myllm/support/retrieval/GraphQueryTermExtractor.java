package com.example.myllm.support.retrieval;

import com.example.myllm.support.graph.EntityNameNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户问题提取 Neo4j 实体种子检索词。
 *
 * <p>优先保留完整问句，再补充中文连续词组和去停用词后的片段，供精确匹配和全文索引共用。</p>
 */
public final class GraphQueryTermExtractor {

    private static final Pattern CHINESE_PHRASE = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
    private static final Pattern STOP_WORD_SUFFIX =
            Pattern.compile("(是什么|什么是|含义|介绍|说明|流程|步骤)$");

    private GraphQueryTermExtractor() {
    }

    public static List<String> extract(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        String normalized = EntityNameNormalizer.normalize(query);
        if (!normalized.isBlank()) {
            terms.add(normalized);
            String trimmed = STOP_WORD_SUFFIX.matcher(normalized).replaceFirst("").strip();
            if (!trimmed.isBlank() && !trimmed.equals(normalized)) {
                terms.add(trimmed);
            }
            for (String part : normalized.split("的")) {
                String segment = part.strip();
                if (segment.length() >= 2) {
                    terms.add(segment);
                }
            }
        }
        Matcher matcher = CHINESE_PHRASE.matcher(normalized);
        while (matcher.find()) {
            String phrase = matcher.group().strip();
            if (phrase.length() >= 2) {
                terms.add(phrase);
            }
        }
        return List.copyOf(new ArrayList<>(terms));
    }

    /** 拼接供 Neo4j 全文索引使用的查询文本。 */
    public static String toFulltextQuery(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        return String.join(" OR ", terms.stream()
                .map(GraphQueryTermExtractor::escapeFulltextTerm)
                .filter(term -> !term.isBlank())
                .toList());
    }

    private static String escapeFulltextTerm(String term) {
        if (term == null || term.isBlank()) {
            return "";
        }
        String escaped = term.replace("\"", "\\\"");
        if (escaped.contains(" ") || escaped.contains(":")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
