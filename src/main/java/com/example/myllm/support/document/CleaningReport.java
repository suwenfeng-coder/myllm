package com.example.myllm.support.document;

import java.util.List;
import java.util.Map;

/**
 * 数据清理的统计与审计摘要。
 *
 * @param cleanerVersion 清理规则版本
 * @param rawCharCount 清理前 Unicode 字符数
 * @param cleanedCharCount 清理后 Unicode 字符数
 * @param removedCharCount 移除字符数
 * @param removalRatio 移除字符占清理前内容的比例
 * @param removedDuplicateBlocks 移除的完全重复段落数
 * @param ruleAffectedCounts 各清理规则的命中次数
 * @param warnings 不阻断处理的质量警告
 */
public record CleaningReport(
        String cleanerVersion,
        int rawCharCount,
        int cleanedCharCount,
        int removedCharCount,
        double removalRatio,
        int removedDuplicateBlocks,
        Map<String, Integer> ruleAffectedCounts,
        List<String> warnings) {

    public CleaningReport {
        ruleAffectedCounts = ruleAffectedCounts == null ? Map.of() : Map.copyOf(ruleAffectedCounts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
