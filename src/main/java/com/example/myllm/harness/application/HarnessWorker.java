package com.example.myllm.harness.application;

import com.example.myllm.harness.config.HarnessProperties;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 轮询 QUEUED 运行并委托 {@link HarnessOrchestrator} 执行。 */
@Component
@ConditionalOnProperty(name = "harness.enabled", havingValue = "true")
public class HarnessWorker {

    private static final Logger log = LoggerFactory.getLogger(HarnessWorker.class);

    private final HarnessRunService runService;
    private final HarnessRunExecutor runExecutor;
    private final HarnessProperties properties;
    private final String workerId;
    private final Semaphore concurrency;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "harness-worker-exec");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "harness-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public HarnessWorker(
            HarnessRunService runService,
            HarnessRunExecutor runExecutor,
            HarnessProperties properties) {
        this.runService = runService;
        this.runExecutor = runExecutor;
        this.properties = properties;
        this.workerId = resolveWorkerId();
        this.concurrency = new Semaphore(properties.getWorker().getMaxConcurrency());
    }

    @Scheduled(fixedDelayString = "${harness.worker.poll-interval-ms:1000}")
    public void poll() {
        while (concurrency.tryAcquire()) {
            Optional<HarnessRunService.ClaimedRun> claimed = runService.claimNext(workerId);
            if (claimed.isEmpty()) {
                concurrency.release();
                return;
            }
            HarnessRunService.ClaimedRun job = claimed.get();
            executor.submit(() -> runJob(job));
        }
    }

    private void runJob(HarnessRunService.ClaimedRun claimed) {
        String runId = claimed.run().getRunId();
        long heartbeatIntervalMs = Math.min(
                properties.getWorker().getHeartbeatIntervalMs(),
                Math.max(1000, properties.getWorker().getLeaseDurationMs() / 3));
        ScheduledFuture<?> heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(
                () -> heartbeatQuietly(runId, claimed.leaseToken()),
                heartbeatIntervalMs,
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS);
        try {
            runExecutor.execute(claimed, () -> heartbeatQuietly(runId, claimed.leaseToken()));
        } catch (Exception e) {
            log.warn("Harness 运行 {} 未捕获异常: {}", runId, e.getMessage());
        } finally {
            heartbeatTask.cancel(false);
            concurrency.release();
        }
    }

    private void heartbeatQuietly(String runId, String leaseToken) {
        try {
            runService.heartbeat(runId, leaseToken);
        } catch (Exception e) {
            log.debug("心跳失败 runId={}: {}", runId, e.getMessage());
        }
    }

    String workerId() {
        return workerId;
    }

    /** 应用停止时释放线程池，避免测试上下文或热重载遗留后台线程。 */
    @PreDestroy
    void shutdownExecutors() {
        executor.shutdownNow();
        heartbeatExecutor.shutdownNow();
    }

    private static String resolveWorkerId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "harness-worker-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
