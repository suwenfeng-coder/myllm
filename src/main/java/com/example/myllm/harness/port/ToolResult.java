package com.example.myllm.harness.port;

/** 工具执行结果 envelope。 */
public record ToolResult<T>(
        boolean success,
        T output,
        String resultPreview,
        String errorCode,
        String errorMessage,
        long durationMs) {

    public static <T> ToolResult<T> ok(T output, String preview, long durationMs) {
        return new ToolResult<>(true, output, preview, null, null, durationMs);
    }

    public static <T> ToolResult<T> failed(String errorCode, String message, long durationMs) {
        return new ToolResult<>(false, null, null, errorCode, message, durationMs);
    }
}
