package com.example.myllm.harness.domain;

/** Harness 步骤类型。 */
public enum StepType {
    INTAKE,
    CONTEXT_BUILD,
    MODEL_DECISION,
    POLICY_CHECK,
    APPROVAL,
    TOOL_EXECUTION,
    VERIFICATION,
    REPAIR,
    FINAL_RESPONSE,
    COMPENSATION
}
