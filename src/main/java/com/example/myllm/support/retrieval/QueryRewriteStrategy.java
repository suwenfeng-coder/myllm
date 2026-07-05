package com.example.myllm.support.retrieval;

/**
 * 问题改写策略，用于日志与后续 LLM 改写扩展。
 */
public enum QueryRewriteStrategy {
    /** 改写功能关闭，检索使用原始问题 */
    DISABLED,
    /** 规则预处理：规范化后直接用于检索 */
    RULE_NORMALIZED,
    /** 规则预处理：文件名定位类，使用书名/文件名线索拼接检索词 */
    RULE_FILENAME_HINT
}
