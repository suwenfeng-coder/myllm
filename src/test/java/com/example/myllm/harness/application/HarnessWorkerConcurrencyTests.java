package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myllm.harness.config.HarnessConfiguration;
import com.example.myllm.harness.domain.RunStatus;
import com.example.myllm.harness.domain.RunType;
import com.example.myllm.harness.repository.HarnessRunRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({
        HarnessRunService.class,
        HarnessEventService.class,
        HarnessConfiguration.class,
        HarnessWorker.class,
        HarnessWorkerConcurrencyTests.RecordingExecutorConfig.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class
})
@TestPropertySource(properties = {
        "harness.enabled=true",
        "harness.worker.poll-interval-ms=5000",
        "harness.worker.max-concurrency=2"
})
class HarnessWorkerConcurrencyTests {

    @Autowired
    private HarnessWorker harnessWorker;

    @Autowired
    private HarnessRunService harnessRunService;

    @Autowired
    private HarnessRunRepository runRepository;

    @Autowired
    private RecordingRunExecutor recordingRunExecutor;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoQueuedRunsClaimedByWorkerWithoutDuplicateExecution() throws InterruptedException {
        harnessRunService.createRun(command("client-a"));
        harnessRunService.createRun(command("client-b"));

        recordingRunExecutor.reset(2);
        harnessWorker.poll();

        assertTrue(recordingRunExecutor.awaitCompletion(5, TimeUnit.SECONDS));
        assertEquals(2, recordingRunExecutor.executionCount());

        long succeeded = runRepository.findAll().stream()
                .filter(r -> r.getStatus() == RunStatus.SUCCEEDED)
                .count();
        assertEquals(2, succeeded);
    }

    private static HarnessRunService.CreateRunCommand command(String clientRequestId) {
        return new HarnessRunService.CreateRunCommand(
                "knowledge-assistant",
                1,
                "hash-" + clientRequestId,
                RunType.AGENT_LOOP,
                "objective",
                clientRequestId,
                null,
                null,
                8);
    }

    @TestConfiguration
    static class RecordingExecutorConfig {

        @Bean
        @Primary
        RecordingRunExecutor recordingRunExecutor(HarnessRunService runService) {
            return new RecordingRunExecutor(runService);
        }
    }

    static class RecordingRunExecutor implements HarnessRunExecutor {

        private final HarnessRunService runService;
        private final AtomicInteger executionCount = new AtomicInteger();
        private CountDownLatch latch = new CountDownLatch(1);

        RecordingRunExecutor(HarnessRunService runService) {
            this.runService = runService;
        }

        void reset(int expected) {
            executionCount.set(0);
            latch = new CountDownLatch(expected);
        }

        int executionCount() {
            return executionCount.get();
        }

        boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        @Override
        public void execute(HarnessRunService.ClaimedRun claimed, Runnable heartbeat) {
            executionCount.incrementAndGet();
            runService.completeRun(claimed.run().getRunId(), claimed.leaseToken(), "stub-ok", null);
            latch.countDown();
        }
    }
}
