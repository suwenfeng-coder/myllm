package com.example.myllm.harness.domain;

/** Harness 领域错误码。 */
public enum HarnessErrorCode {
    INVALID_STATE_TRANSITION,
    LEASE_MISMATCH,
    LEASE_EXPIRED,
    BUDGET_EXHAUSTED,
    TOOL_NOT_ALLOWED,
    APPROVAL_REQUIRED,
    APPROVAL_EXPIRED,
    DUPLICATE_CLIENT_REQUEST,
    RUN_NOT_FOUND,
    VALIDATION_FAILED,
    RUN_CANCELLED
}
