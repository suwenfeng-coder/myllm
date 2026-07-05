package com.example.myllm.support.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 根据章节编号规则修复标题层级并去除连续重复标题。 */
public final class HeadingLevelRepairer {

    private static final Pattern CHAPTER = Pattern.compile("^第[0-9一二三四五六七八九十百]+章.*");
    private static final Pattern SECTION = Pattern.compile("^第[0-9一二三四五六七八九十百]+节.*");
    private static final Pattern FORMAT = Pattern.compile("^格式\\s*\\d+.*");
    private static final Pattern NUMBERED = Pattern.compile("^[0-9一二三四五六七八九十]+[、.．].*");

    private HeadingLevelRepairer() {
    }

    public static List<DocumentBlock> repair(List<DocumentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<DocumentBlock> result = new ArrayList<>(blocks.size());
        String previousHeading = null;
        for (DocumentBlock block : blocks) {
            if (block.type() != DocumentBlockType.HEADING) {
                result.add(block);
                previousHeading = null;
                continue;
            }
            String content = block.content().strip();
            if (content.equals(previousHeading)) {
                continue;
            }
            int level = inferLevel(content, block.headingLevel());
            result.add(new DocumentBlock(
                    DocumentBlockType.HEADING, content, level, block.sourceIndex()));
            previousHeading = content;
        }
        return result;
    }

    static int inferLevel(String content, Integer existingLevel) {
        if (CHAPTER.matcher(content).matches()) {
            return 1;
        }
        if (SECTION.matcher(content).matches() || FORMAT.matcher(content).matches()) {
            return 2;
        }
        if (NUMBERED.matcher(content).matches()) {
            return 3;
        }
        if (existingLevel != null && existingLevel == 1) {
            return 1;
        }
        if (existingLevel != null && existingLevel >= 1 && existingLevel <= 6) {
            if (CHAPTER.matcher(content).matches()) {
                return 1;
            }
            if (SECTION.matcher(content).matches() || FORMAT.matcher(content).matches()) {
                return Math.max(2, existingLevel);
            }
            return existingLevel;
        }
        if (content.length() <= 12) {
            return 3;
        }
        return 2;
    }
}
