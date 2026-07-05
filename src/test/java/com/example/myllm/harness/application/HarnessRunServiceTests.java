package com.example.myllm.harness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.myllm.harness.config.HarnessConfiguration;
import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.example.myllm.harness.domain.RunStatus;
import com.example.myllm.harness.domain.RunType;
import com.example.myllm.harness.entity.HarnessRun;
import com.example.myllm.harness.repository.HarnessEventRepository;
import com.example.myllm.harness.repository.HarnessRunRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@Import({
        HarnessRunService.class,
        HarnessEventService.class,
        HarnessConfiguration.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class
})
@TestPropertySource(properties = {
        "harness.enabled=false",
        "harness.worker.lease-duration-ms=30000"
})
class HarnessRunServiceTests {

    private final HarnessRunService harnessRunService;
    private final HarnessRunRepository runRepository;
    private final HarnessEventRepository eventRepository;

    @Autowired
    HarnessRunServiceTests(
            HarnessRunService harnessRunService,
            HarnessRunRepository runRepository,
            HarnessEventRepository eventRepository) {
        this.harnessRunService = harnessRunService;
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
    }

    @Test
    void createRunIsIdempotentByClientRequestId() {
        HarnessRunService.CreateRunCommand command = new HarnessRunService.CreateRunCommand(
                "knowledge-assistant",
                1,
                "abc123hash",
                RunType.AGENT_LOOP,
                "测试目标",
                "client-req-001",
                "trace-1",
                null,
                8);

        HarnessRun first = harnessRunService.createRun(command);
        HarnessRun second = harnessRunService.createRun(command);

        assertEquals(first.getRunId(), second.getRunId());
        assertEquals(RunStatus.QUEUED, second.getStatus());
        assertFalse(eventRepository.findByRunIdOrderBySequenceNoAsc(first.getRunId()).isEmpty());
    }

    @Test
    void claimCompleteAndFencing() {
        HarnessRun run = harnessRunService.createRun(sampleCommand("client-req-002"));
        HarnessRunService.ClaimedRun claimed = harnessRunService.claimNext("worker-a").orElseThrow();
        assertEquals(run.getRunId(), claimed.run().getRunId());
        assertEquals(RunStatus.RUNNING, claimed.run().getStatus());

        harnessRunService.heartbeat(run.getRunId(), claimed.leaseToken());

        HarnessRun completed = harnessRunService.completeRun(
                run.getRunId(), claimed.leaseToken(), "done", null);
        assertEquals(RunStatus.SUCCEEDED, completed.getStatus());
    }

    @Test
    void staleLeaseCannotCompleteAfterReclaim() {
        HarnessRun run = harnessRunService.createRun(sampleCommand("client-req-003"));
        HarnessRunService.ClaimedRun firstClaim = harnessRunService.claimNext("worker-old").orElseThrow();
        String staleToken = firstClaim.leaseToken();

        HarnessRun entity = runRepository.findById(run.getRunId()).orElseThrow();
        entity.setLeaseExpiresAt(LocalDateTime.now(ZoneId.systemDefault()).minusMinutes(1));
        runRepository.saveAndFlush(entity);

        harnessRunService.recoverExpiredLeases();
        HarnessRunService.ClaimedRun secondClaim = harnessRunService.claimNext("worker-new").orElseThrow();
        assertNotEquals(staleToken, secondClaim.leaseToken());

        assertEquals(
                HarnessErrorCode.LEASE_MISMATCH,
                assertThrows(
                        HarnessDomainException.class,
                        () -> harnessRunService.completeRun(run.getRunId(), staleToken, "stale", null))
                        .getErrorCode());

        harnessRunService.completeRun(run.getRunId(), secondClaim.leaseToken(), "ok", null);
        assertEquals(RunStatus.SUCCEEDED, runRepository.findById(run.getRunId()).orElseThrow().getStatus());
    }

    @Test
    void queuedRunIsCancelledImmediately() {
        HarnessRun run = harnessRunService.createRun(sampleCommand("client-req-cancel-queued"));

        HarnessRun cancelled = harnessRunService.requestCancel(run.getRunId());

        assertEquals(RunStatus.CANCELLED, cancelled.getStatus());
        assertEquals(HarnessErrorCode.RUN_CANCELLED.name(), cancelled.getErrorCode());
    }

    @Test
    void runningRunRequiresCurrentLeaseToBecomeCancelled() {
        HarnessRun run = harnessRunService.createRun(sampleCommand("client-req-cancel-running"));
        HarnessRunService.ClaimedRun claimed = harnessRunService.claimNext("worker-cancel").orElseThrow();

        HarnessRun cancellationRequested = harnessRunService.requestCancel(run.getRunId());
        assertEquals(RunStatus.RUNNING, cancellationRequested.getStatus());
        assertEquals(true, cancellationRequested.isCancelRequested());

        HarnessRun cancelled = harnessRunService.cancelRun(run.getRunId(), claimed.leaseToken(), "用户取消");
        assertEquals(RunStatus.CANCELLED, cancelled.getStatus());
    }

    private static HarnessRunService.CreateRunCommand sampleCommand(String clientRequestId) {
        return new HarnessRunService.CreateRunCommand(
                "knowledge-assistant",
                1,
                "hash-sample",
                RunType.AGENT_LOOP,
                "objective",
                clientRequestId,
                null,
                null,
                8);
    }
}
