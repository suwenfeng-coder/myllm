package com.example.myllm.support.document;

import java.util.ArrayList;
import java.util.List;

/** 根据章节编号规则修复标题层级并去除连续重复标题。 */
public final class HeadingLevelRepairer {

    private HeadingLevelRepairer() {
    }

    public static List<DocumentBlock> repair(List<DocumentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<DocumentBlock> result = new ArrayList<>(blocks.size());
        String previousHeading = null;
        for (DocumentBlock block : blocks) {
            RepairedBlock repaired = repairBlock(block, previousHeading);
            if (repaired.block() != null) {
                result.add(repaired.block());
            }
            previousHeading = repaired.previousHeading();
        }
        return result;
    }

    static int inferLevel(String content, Integer existingLevel) {
        HeadingKind kind = classify(content);
        if (kind == HeadingKind.CHAPTER) {
            return 1;
        }
        if (kind == HeadingKind.SECTION_OR_FORMAT) {
            return 2;
        }
        if (kind == HeadingKind.NUMBERED) {
            return 3;
        }
        if (existingLevel != null && existingLevel == 1) {
            return 1;
        }
        if (existingLevel != null && existingLevel >= 1 && existingLevel <= 6) {
            return existingLevel;
        }
        if (content.length() <= 12) {
            return 3;
        }
        return 2;
    }

    private static RepairedBlock repairBlock(DocumentBlock block, String previousHeading) {
        if (block.type() != DocumentBlockType.HEADING) {
            return new RepairedBlock(block, null);
        }
        String content = block.content().strip();
        if (content.equals(previousHeading)) {
            return new RepairedBlock(null, previousHeading);
        }
        int level = inferLevel(content, block.headingLevel());
        return new RepairedBlock(
                new DocumentBlock(DocumentBlockType.HEADING, content, level, block.sourceIndex()),
                content);
    }

    private static HeadingKind classify(String content) {
        if (isChineseOrdinalHeading(content, '章')) {
            return HeadingKind.CHAPTER;
        }
        if (isChineseOrdinalHeading(content, '节') || isFormatHeading(content)) {
            return HeadingKind.SECTION_OR_FORMAT;
        }
        if (isNumberedHeading(content)) {
            return HeadingKind.NUMBERED;
        }
        return HeadingKind.OTHER;
    }

    private static boolean isChineseOrdinalHeading(String content, char suffix) {
        return content.length() >= 3
                && content.charAt(0) == '第'
                && content.indexOf(suffix, 2) > 1
                && allOrdinalChars(content, 1, content.indexOf(suffix, 2));
    }

    private static boolean allOrdinalChars(String content, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            char ch = content.charAt(i);
            if (!Character.isDigit(ch) && "一二三四五六七八九十百".indexOf(ch) < 0) {
                return false;
            }
        }
        return endExclusive > startInclusive;
    }

    private static boolean isFormatHeading(String content) {
        if (!content.startsWith("格式")) {
            return false;
        }
        int index = 2;
        while (index < content.length() && Character.isWhitespace(content.charAt(index))) {
            index++;
        }
        return index < content.length() && Character.isDigit(content.charAt(index));
    }

    private static boolean isNumberedHeading(String content) {
        int markerIndex = firstMarkerIndex(content);
        return markerIndex > 0 && allOrdinalChars(content, 0, markerIndex);
    }

    private static int firstMarkerIndex(String content) {
        int chineseComma = content.indexOf('、');
        int dot = content.indexOf('.');
        int fullWidthDot = content.indexOf('．');
        int marker = minNonNegative(chineseComma, dot);
        return minNonNegative(marker, fullWidthDot);
    }

    private static int minNonNegative(int left, int right) {
        if (left < 0) {
            return right;
        }
        if (right < 0) {
            return left;
        }
        return Math.min(left, right);
    }

    private enum HeadingKind {
        CHAPTER,
        SECTION_OR_FORMAT,
        NUMBERED,
        OTHER
    }

    private record RepairedBlock(DocumentBlock block, String previousHeading) {}
}
