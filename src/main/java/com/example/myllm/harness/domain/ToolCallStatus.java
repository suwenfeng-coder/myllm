package com.example.myllm.harness.domain;

/** 工具调用状态。 */
public enum ToolCallStatus {
    PENDING,
    WAITING_APPROVAL,
    APPROVED,
    REJECTED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
