package com.example.myllm.harness.domain;

/** 单步执行状态。 */
public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
