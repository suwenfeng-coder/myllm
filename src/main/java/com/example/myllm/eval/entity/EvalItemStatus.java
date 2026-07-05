package com.example.myllm.eval.entity;

/** 单题评测状态。 */
public enum EvalItemStatus {
    PENDING,
    ANSWERING,
    SCORING,
    DONE,
    FAILED,
    SKIPPED
}
