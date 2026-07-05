package com.example.myllm.support.chunking;

import java.util.Locale;

/**
 * 文件向量化支持的分块策略。
 *
 * <p>{@link #SMART} 是路由策略，实际执行时会根据文档结构和长度选择
 * {@link #DOCUMENT}、{@link #SEMANTIC} 或 {@link #RECURSIVE}。</p>
 */
public enum ChunkStrategy {
    FIXED,
    RECURSIVE,
    DOCUMENT,
    SEMANTIC,
    SMART;

    /**
     * 将接口参数转换为分块策略；空值保持对旧接口的兼容，默认使用固定分块。
     *
     * @param value 接口传入的策略名称，不区分大小写
     * @return 对应的分块策略
     * @throws IllegalArgumentException 策略名称不受支持时抛出
     */
    public static ChunkStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return FIXED;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "不支持的分块策略: " + value + "，可选值: fixed/recursive/document/semantic/smart");
        }
    }

    /**
     * @return 用于 HTTP 接口和数据库日志的小写策略名
     */
    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
