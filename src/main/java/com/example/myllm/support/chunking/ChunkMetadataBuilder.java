package com.example.myllm.support.chunking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * 分块元数据计算：字符数、Token 估算、内容哈希。
 */
final class ChunkMetadataBuilder {

    private ChunkMetadataBuilder() {
    }

    static int charCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.codePointCount(0, text.length());
    }

    static int estimateTokenCount(String text) {
        return BgeM3TokenEstimator.estimate(text);
    }

    static String contentHash(String embeddingContent) {
        String normalized = normalizeForHash(embeddingContent);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String normalizeForHash(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
    }
}
