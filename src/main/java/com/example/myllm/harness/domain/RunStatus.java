package com.example.myllm.harness.domain;

/** Harness 运行生命周期状态。 */
public enum RunStatus {
    CREATED,
    QUEUED,
    PLANNING,
    RUNNING,
    WAITING_APPROVAL,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
