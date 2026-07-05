package com.example.myllm.support.chunking;

/**
 * bge-m3 对齐的 Token 估算器。
 *
 * <p>中文按约 1 token/字、英文按约 4 字符/token 估算，用于分块边界控制。</p>
 */
public final class BgeM3TokenEstimator {

    private BgeM3TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i = text.offsetByCodePoints(i, 1)) {
            int codePoint = text.codePointAt(i);
            if (isCjk(codePoint)) {
                cjk++;
            } else if (!Character.isWhitespace(codePoint)) {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 4.0);
    }

    /**
     * 从 start 起截取不超过 maxTokens 的前缀，返回结束索引（不含）。
     */
    public static int endIndexForMaxTokens(String text, int start, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0 || start >= text.length()) {
            return start;
        }
        int end = start;
        while (end < text.length()) {
            int next = text.offsetByCodePoints(end, 1);
            if (estimate(text.substring(start, next)) > maxTokens) {
                break;
            }
            end = next;
        }
        if (end == start) {
            return Math.min(text.length(), text.offsetByCodePoints(start, 1));
        }
        return end;
    }

    /**
     * 从 end 向前回退 overlapTokens 对应的起始位置。
     */
    public static int startIndexForOverlap(String text, int end, int overlapTokens) {
        if (text == null || text.isEmpty() || overlapTokens <= 0 || end <= 0) {
            return 0;
        }
        int start = Math.max(0, end - 1);
        while (start > 0) {
            int candidate = start;
            if (estimate(text.substring(candidate, end)) >= overlapTokens) {
                return candidate;
            }
            start = Math.max(0, text.offsetByCodePoints(candidate, -1));
        }
        return 0;
    }

    private static boolean isCjk(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF);
    }
}
