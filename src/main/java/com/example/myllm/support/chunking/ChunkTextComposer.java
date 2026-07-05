package com.example.myllm.support.chunking;

/**
 * 将章节路径与正文组合为展示文本。
 */
final class ChunkTextComposer {

    private ChunkTextComposer() {
    }

    static String displayText(String headingPath, String embeddingContent) {
        String content = embeddingContent == null ? "" : embeddingContent.strip();
        if (headingPath == null || headingPath.isBlank()) {
            return content;
        }
        if (content.isEmpty()) {
            return "章节路径：" + headingPath;
        }
        return "章节路径：" + headingPath + "\n" + content;
    }
}
