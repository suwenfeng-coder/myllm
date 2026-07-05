package com.example.myllm.support.document;

import java.util.regex.Pattern;

/** 将 Maker/Docling 输出的 HTML 换行标记合并为空格。 */
public final class HtmlLineBreakMerger {

    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");

    private HtmlLineBreakMerger() {
    }

    public static String merge(String content) {
        if (content == null || content.isBlank() || !content.contains("<br")) {
            return content == null ? "" : content;
        }
        String merged = BR_TAG.matcher(content).replaceAll(" ");
        return merged.replaceAll("[\\p{Zs}\\t]{2,}", " ").strip();
    }
}
