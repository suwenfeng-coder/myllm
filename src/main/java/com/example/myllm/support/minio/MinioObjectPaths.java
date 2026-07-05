package com.example.myllm.support.minio;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class MinioObjectPaths {

    private static final DateTimeFormatter DATE_FOLDER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private MinioObjectPaths() {
    }

    public static String dateFolder(LocalDate date) {
        return DATE_FOLDER.format(date);
    }

    public static String originalObjectKey(
            String uploadsPrefix, LocalDate date, String originalFileName) {
        return normalizePrefix(uploadsPrefix) + "/"
                + dateFolder(date) + "/"
                + sanitizeFileName(originalFileName);
    }

    public static String parsedObjectKey(
            String parsedPrefix, LocalDate date, String parsedFileName) {
        return normalizePrefix(parsedPrefix) + "/"
                + dateFolder(date) + "/"
                + sanitizeFileName(parsedFileName);
    }

    public static String parsedFileName(String originalFileName) {
        String baseName = baseNameOf(originalFileName);
        if (baseName.isBlank()) {
            return "parsed.md";
        }
        int dot = baseName.lastIndexOf('.');
        String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
        if (stem.isBlank()) {
            return "parsed.md";
        }
        return stem + ".parsed.md";
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
}
