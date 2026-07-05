package com.example.myllm.support.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户问题中提取文件名线索，并对候选分片做文件名匹配加权。
 */
public final class QueryFilenameMatcher {

    private static final Pattern BOOK_TITLE = Pattern.compile("《([^》]+)》");
    private static final Pattern BOOK_TITLE_WITH_EXTENSION = Pattern.compile(
            "《([^》]+)》\\.(docx|doc|md|txt|csv|html|log)",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> FILE_EXTENSIONS =
            List.of(".docx", ".doc", ".md", ".txt", ".csv", ".html", ".log");

    private QueryFilenameMatcher() {
    }

    public static List<String> extractHints(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> hints = new LinkedHashSet<>();
        Matcher bookMatcher = BOOK_TITLE.matcher(query);
        while (bookMatcher.find()) {
            String title = bookMatcher.group(1).strip();
            if (!title.isEmpty()) {
                hints.add(title);
            }
        }
        Matcher titledFileMatcher = BOOK_TITLE_WITH_EXTENSION.matcher(query);
        while (titledFileMatcher.find()) {
            String title = titledFileMatcher.group(1).strip();
            String extension = titledFileMatcher.group(2).strip();
            if (!title.isEmpty()) {
                hints.add("《" + title + "》." + extension);
            }
        }
        extractFileNames(query, hints);
        return List.copyOf(hints);
    }

    private static void extractFileNames(String query, Set<String> hints) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        for (int dot = lowerQuery.indexOf('.'); dot >= 0; dot = lowerQuery.indexOf('.', dot + 1)) {
            String extension = matchingExtension(lowerQuery, dot);
            if (extension == null) {
                continue;
            }
            int start = dot;
            while (start > 0) {
                int codePoint = query.codePointBefore(start);
                if (!isFileNameCharacter(codePoint)) {
                    break;
                }
                start -= Character.charCount(codePoint);
            }
            if (start < dot) {
                hints.add(query.substring(start, dot + extension.length()).strip());
            }
        }
    }

    private static String matchingExtension(String query, int dot) {
        for (String extension : FILE_EXTENSIONS) {
            if (query.startsWith(extension, dot)) {
                return extension;
            }
        }
        return null;
    }

    private static boolean isFileNameCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
                || codePoint == '_'
                || codePoint == '-'
                || codePoint == '('
                || codePoint == ')'
                || codePoint == '（'
                || codePoint == '）';
    }

    public static double filenameBoost(String query, String fileName, double boostWeight) {
        if (query == null || fileName == null || boostWeight <= 0.0) {
            return 0.0;
        }
        String normalizedFileName = normalize(fileName);
        if (normalizedFileName.isEmpty()) {
            return 0.0;
        }
        double maxBoost = 0.0;
        for (String hint : extractHints(query)) {
            String normalizedHint = normalize(hint);
            if (normalizedHint.isEmpty()) {
                continue;
            }
            if (normalizedFileName.contains(normalizedHint) || normalizedHint.contains(normalizedFileName)) {
                maxBoost = Math.max(maxBoost, boostWeight);
            }
        }
        return maxBoost;
    }

    /**
     * 将书名号、全角括号、大小写和空白归一化，供文件名精确解析与加权共同使用。
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace("《", "")
                .replace("》", "")
                .replace('（', '(')
                .replace('）', ')')
                .replace('【', '[')
                .replace('】', ']')
                .replaceAll("\\s+", "")
                .strip();
    }
}
