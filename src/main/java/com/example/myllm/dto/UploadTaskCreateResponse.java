package com.example.myllm.dto;

/** 异步上传任务创建响应。 */
public record UploadTaskCreateResponse(
        String taskId,
        Long etaSeconds,
        String message) {
}
