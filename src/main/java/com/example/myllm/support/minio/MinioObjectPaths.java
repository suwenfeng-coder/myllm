package com.example.myllm.support.minio;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public final class MinioObjectPaths {

    private static final DateTimeFormatter DATE_FOLDER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern FILE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String UNIQUE_SEPARATOR = "__";

    private MinioObjectPaths() {
    }

    public static String dateFolder(LocalDate date) {
        return DATE_FOLDER.format(date);
    }

    public static String requireValidFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId 不能为空");
        }
        String normalized = fileId.trim();
        if (!FILE_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("fileId 包含非法字符");
        }
        return normalized;
    }

    public static String originalFileName(String fileId, String originalFileName) {
        FileNameParts parts = splitFileName(originalFileName);
        return parts.stem() + UNIQUE_SEPARATOR + requireValidFileId(fileId) + parts.extension();
    }

    public static String parsedFileName(String fileId, String originalFileName) {
        FileNameParts parts = splitFileName(originalFileName);
        return parts.stem() + UNIQUE_SEPARATOR + requireValidFileId(fileId) + ".parsed.md";
    }

    public static String originalObjectKey(
            String uploadsPrefix, LocalDate date, String fileId, String originalFileName) {
        return objectKey(uploadsPrefix, date, originalFileName(fileId, originalFileName));
    }

    public static String parsedObjectKey(
            String parsedPrefix, LocalDate date, String fileId, String originalFileName) {
        return objectKey(parsedPrefix, date, parsedFileName(fileId, originalFileName));
    }

    public static String baseNameOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unknown";
        }
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    public static String sanitizeFileName(String fileName) {
        String baseName = baseNameOf(fileName);
        String sanitized = baseName.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    private static String objectKey(String prefix, LocalDate date, String storedFileName) {
        return normalizePrefix(prefix) + "/" + dateFolder(date) + "/" + storedFileName;
    }

    private static FileNameParts splitFileName(String fileName) {
        String sanitized = sanitizeFileName(fileName);
        int dot = sanitized.lastIndexOf('.');
        if (dot <= 0) {
            return new FileNameParts(sanitized, "");
        }
        return new FileNameParts(sanitized.substring(0, dot), sanitized.substring(dot));
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "files";
        }
        String normalized = prefix.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "files" : normalized;
    }

    private record FileNameParts(String stem, String extension) {
    }
}
