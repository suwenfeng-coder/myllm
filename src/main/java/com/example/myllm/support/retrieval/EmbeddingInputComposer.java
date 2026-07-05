package com.example.myllm.support.retrieval;

/**
 * 构造送入 embedding 模型的文本，使向量检索能感知来源文件名。
 */
public final class EmbeddingInputComposer {

    private static final String PREFIX = "来源文件：";

    private EmbeddingInputComposer() {
    }

    public static String compose(String sourceFileName, String chunkContent) {
        String fileName = sourceFileName == null ? "" : sourceFileName.strip();
        String content = chunkContent == null ? "" : chunkContent.strip();
        if (fileName.isEmpty()) {
            return content;
        }
        if (content.isEmpty()) {
            return PREFIX + fileName;
        }
        return PREFIX + fileName + "\n" + content;
    }
}
