package com.example.myllm.dto;

/** 上传任务进度查询响应。 */
public record UploadTaskStatusResponse(
        String taskId,
        String status,
        String phase,
        int percent,
        String message,
        Long etaSeconds,
        String fileId,
        String docforgeJobId,
        boolean graphIndexEnqueued,
        String errorMessage) {
}
