package com.example.myllm.entity;

/** 文档上传向量化任务生命周期状态。 */
public enum UploadTaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
