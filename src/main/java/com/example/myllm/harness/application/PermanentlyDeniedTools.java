package com.example.myllm.harness.application;

import java.util.Locale;
import java.util.Set;

/** ADR-001 永久禁止及默认拦截的工具名。 */
public final class PermanentlyDeniedTools {

    public static final Set<String> DENIED = Set.of(
            "shell.execute",
            "sql.execute",
            "cypher.execute",
            "http.request",
            "file.delete",
            "env.read",
            "filesystem.read",
            "filesystem.write");

    private PermanentlyDeniedTools() {
    }

    public static boolean isDenied(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return true;
        }
        return DENIED.contains(toolName.trim().toLowerCase(Locale.ROOT));
    }
}
