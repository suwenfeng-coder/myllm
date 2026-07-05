package com.example.myllm.support.chunking;

/**
 * 对最终 Chunk 做统一空白规范化。
 *
 * <p>普通文本移除行首尾空格并限制为最多一个空行；围栏代码块内部保留缩进，
 * 仅清除行尾空格。该处理在所有分块策略之后统一执行。</p>
 */
public final class ChunkWhitespaceNormalizer {

    private ChunkWhitespaceNormalizer() {
    }

    /**
     * @param content 分块器产生的文本
     * @return 结构不变但空白更紧凑的文本
     */
    public static String normalize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder result = new StringBuilder(normalized.length());
        boolean inCode = false;
        boolean previousBlank = false;
        for (String line : normalized.split("\\n", -1)) {
            String markerCandidate = line.strip();
            boolean fence = markerCandidate.startsWith("```");
            String cleanedLine = inCode ? stripTrailing(line) : markerCandidate;
            boolean blank = cleanedLine.isBlank();
            if (!inCode && blank && previousBlank) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(cleanedLine);
            previousBlank = !inCode && blank;
            if (fence) {
                inCode = !inCode;
                previousBlank = false;
            }
        }
        return result.toString().strip();
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
