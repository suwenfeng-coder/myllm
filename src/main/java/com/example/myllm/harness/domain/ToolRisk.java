package com.example.myllm.harness.domain;

/** 工具风险等级，决定审批与自动执行策略。 */
public enum ToolRisk {
    READ_ONLY,
    SENSITIVE_READ,
    WRITE,
    DESTRUCTIVE,
    EXTERNAL
}
