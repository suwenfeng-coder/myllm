package com.example.myllm.support.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块实现共享的无状态算法工具，限定在 chunking 包内使用。
 */
final class ChunkingSupport {

    private ChunkingSupport() {
    }

    /**
     * 按固定字符窗口切分，保留项目原有行为作为兼容策略。
     */
    static List<String> fixed(String text, int size) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        int safeSize = Math.max(200, size);
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < normalized.length(); start += safeSize) {
            int end = Math.min(normalized.length(), start + safeSize);
            chunks.add(normalized.substring(start, end));
        }
        return chunks;
    }

    /**
     * 按 Token 上限切分，块间保留 overlapTokens 重叠。
     */
    static List<String> fixedByTokens(String text, int maxTokens, int overlapTokens) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        int safeMax = Math.max(80, maxTokens);
        int safeOverlap = Math.max(0, Math.min(overlapTokens, safeMax / 4));
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = BgeM3TokenEstimator.endIndexForMaxTokens(normalized, start, safeMax);
            String chunk = normalized.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = BgeM3TokenEstimator.startIndexForOverlap(normalized, end, safeOverlap);
            if (start >= end) {
                start = end;
            }
        }
        return chunks;
    }

    /**
     * 按 Token 上限递归边界切分。
     */
    static List<String> recursiveByTokens(String text, int maxTokens, int minTokens, int overlapTokens) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        int safeMax = Math.max(80, maxTokens);
        int safeMin = Math.max(40, Math.min(minTokens, safeMax / 2));
        int safeOverlap = Math.max(0, Math.min(overlapTokens, safeMax / 4));
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = BgeM3TokenEstimator.endIndexForMaxTokens(normalized, start, safeMax);
            int end = hardEnd >= normalized.length()
                    ? hardEnd
                    : findTokenAwareBoundary(normalized, start, safeMin, safeMax, hardEnd);
            if (end <= start) {
                end = hardEnd;
            }
            String chunk = normalized.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            int nextStart = BgeM3TokenEstimator.startIndexForOverlap(normalized, end, safeOverlap);
            start = nextStart >= end ? end : skipLeadingWhitespace(normalized, nextStart);
        }
        return chunks;
    }

    private static int findTokenAwareBoundary(String text, int start, int minTokens, int maxTokens, int hardEnd) {
        int minEnd = BgeM3TokenEstimator.endIndexForMaxTokens(text, start, minTokens);
        int boundary = lastIndexOf(text, "\n\n", minEnd, hardEnd);
        if (boundary >= 0) {
            return boundary + 2;
        }
        boundary = lastIndexOfAny(text, "\n", minEnd, hardEnd);
        if (boundary >= 0) {
            return boundary + 1;
        }
        boundary = lastIndexOfAny(text, "。！？!?；;", minEnd, hardEnd);
        if (boundary >= 0) {
            return boundary + 1;
        }
        boundary = lastIndexOfAny(text, "，,:：", minEnd, hardEnd);
        if (boundary >= 0) {
            return boundary + 1;
        }
        boundary = lastIndexOfAny(text, " \t", minEnd, hardEnd);
        if (boundary >= 0) {
            return boundary + 1;
        }
        return BgeM3TokenEstimator.endIndexForMaxTokens(text, start, maxTokens);
    }

    /**
     * 按多级自然边界递归降级切分，并在相邻块之间保留有限重叠。
     * 边界优先级为：段落、换行、句末标点、次级标点、空白、硬切。
     */
    static List<String> recursive(String text, int maxSize, int minSize, int overlap) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        int safeMax = Math.max(200, maxSize);
        int safeMin = Math.max(50, Math.min(minSize, safeMax / 2));
        int safeOverlap = Math.max(0, Math.min(overlap, safeMax / 4));
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(normalized.length(), start + safeMax);
            int end = hardEnd == normalized.length()
                    ? hardEnd
                    : findBoundary(normalized, start + safeMin, hardEnd);
            if (end <= start) {
                end = hardEnd;
            }
            String chunk = normalized.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            int nextStart = Math.max(start + 1, end - safeOverlap);
            start = skipLeadingWhitespace(normalized, nextStart);
        }
        return chunks;
    }

    static String normalize(String text) {
        return text == null ? "" : text.strip();
    }

    private static int findBoundary(String text, int minEnd, int maxEnd) {
        int boundary = lastIndexOf(text, "\n\n", minEnd, maxEnd);
        if (boundary >= 0) {
            return boundary + 2;
        }
        boundary = lastIndexOfAny(text, "\n", minEnd, maxEnd);
        if (boundary >= 0) {
            return boundary + 1;
        }
        boundary = lastIndexOfAny(text, "。！？!?；;", minEnd, maxEnd);
        if (boundary >= 0) {
            return boundary + 1;
        }
        boundary = lastIndexOfAny(text, "，,:：", minEnd, maxEnd);
        if (boundary >= 0) {
            return boundary + 1;
        }
        boundary = lastIndexOfAny(text, " \t", minEnd, maxEnd);
        return boundary >= 0 ? boundary + 1 : maxEnd;
    }

    private static int lastIndexOf(String text, String token, int minEnd, int maxEnd) {
        int index = text.lastIndexOf(token, maxEnd - 1);
        return index >= minEnd ? index : -1;
    }

    private static int lastIndexOfAny(String text, String candidates, int minEnd, int maxEnd) {
        for (int i = maxEnd - 1; i >= minEnd; i--) {
            if (candidates.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipLeadingWhitespace(String text, int start) {
        int index = start;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }
}
