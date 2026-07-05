package com.example.myllm.support;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件二进制探测：在按扩展名解析前识别真实格式。
 */
final class FileContentProbe {

    enum BinaryFormat {
        EMPTY,
        ZIP,
        OLE,
        RTF,
        PLAIN_TEXT,
        UNKNOWN
    }

    private FileContentProbe() {
    }

    static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("读取上传文件失败", e);
        }
    }

    static BinaryFormat detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return BinaryFormat.EMPTY;
        }
        if (bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B) {
            return BinaryFormat.ZIP;
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0xD0
                && (bytes[1] & 0xFF) == 0xCF
                && (bytes[2] & 0xFF) == 0x11
                && (bytes[3] & 0xFF) == 0xE0) {
            return BinaryFormat.OLE;
        }
        if (bytes.length >= 5) {
            String prefix = new String(bytes, 0, 5, StandardCharsets.US_ASCII);
            if ("{\\rtf".equals(prefix)) {
                return BinaryFormat.RTF;
            }
        }
        if (looksLikePlainText(bytes)) {
            return BinaryFormat.PLAIN_TEXT;
        }
        return BinaryFormat.UNKNOWN;
    }

    static boolean isWordLockFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String baseName = fileName;
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (slash >= 0) {
            baseName = fileName.substring(slash + 1);
        }
        return baseName.startsWith("~$");
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String decodePlainText(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) {
            return utf8.trim();
        }
        Charset gbk = Charset.forName("GBK");
        return new String(bytes, gbk).trim();
    }

    private static boolean looksLikePlainText(byte[] bytes) {
        int sample = Math.min(bytes.length, 8192);
        int suspicious = 0;
        for (int i = 0; i < sample; i++) {
            int value = bytes[i] & 0xFF;
            if (value == 0x09 || value == 0x0A || value == 0x0D) {
                continue;
            }
            if (value < 0x20 || value == 0x7F) {
                suspicious++;
            }
        }
        return suspicious * 100 / Math.max(1, sample) < 3;
    }
}
