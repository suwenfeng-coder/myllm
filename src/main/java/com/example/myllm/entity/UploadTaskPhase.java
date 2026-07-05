package com.example.myllm.entity;

/** 上传流水线当前阶段，用于进度展示。 */
public enum UploadTaskPhase {
    QUEUED,
    PARSING,
    STORING,
    CLEANING,
    CHUNKING,
    EMBEDDING,
    COMPLETED
}
