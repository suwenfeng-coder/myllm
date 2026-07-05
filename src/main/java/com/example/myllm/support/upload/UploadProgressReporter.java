package com.example.myllm.support.upload;

import com.example.myllm.entity.UploadTaskPhase;
import com.example.myllm.entity.UploadTaskStatus;

/** 上传流水线进度上报。 */
@FunctionalInterface
public interface UploadProgressReporter {

    void report(
            UploadTaskStatus status,
            UploadTaskPhase phase,
            int percent,
            String message,
            Long etaSeconds,
            String docforgeJobId);

    static UploadProgressReporter noop() {
        return (status, phase, percent, message, eta, jobId) -> {};
    }
}
