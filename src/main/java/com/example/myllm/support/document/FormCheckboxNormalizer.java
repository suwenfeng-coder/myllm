package com.example.myllm.support.document;

/** 将 PDF 表单复选框私有区字符规范为 Markdown 标记。 */
public final class FormCheckboxNormalizer {

    private FormCheckboxNormalizer() {
    }

    public static String normalize(String content) {
        if (content == null || content.isBlank()) {
            return content == null ? "" : content;
        }
        StringBuilder normalized = new StringBuilder(content.length() + 16);
        for (int offset = 0; offset < content.length(); offset = content.offsetByCodePoints(offset, 1)) {
            int codePoint = content.codePointAt(offset);
            if (isChecked(codePoint)) {
                normalized.append("[x]");
            } else if (isUnchecked(codePoint)) {
                normalized.append("[ ]");
            } else {
                normalized.appendCodePoint(codePoint);
            }
        }
        return normalized.toString();
    }

    private static boolean isChecked(int codePoint) {
        return codePoint == '\uF052' || codePoint == '\uF0FE' || codePoint == '\u2611';
    }

    private static boolean isUnchecked(int codePoint) {
        return codePoint == '\uF0A3' || codePoint == '\uF0A8' || codePoint == '\u2610';
    }
}
