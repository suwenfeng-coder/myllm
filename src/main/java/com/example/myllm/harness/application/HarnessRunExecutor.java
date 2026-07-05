package com.example.myllm.harness.application;

/** 执行已领取的 Harness 运行。 */
public interface HarnessRunExecutor {

    void execute(HarnessRunService.ClaimedRun claimed, Runnable heartbeat);
}
