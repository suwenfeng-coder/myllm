package com.example.myllm.harness.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.myllm.config.GlobalExceptionHandler;
import com.example.myllm.harness.application.HarnessDefinitionRegistry;
import com.example.myllm.harness.application.HarnessEventService;
import com.example.myllm.harness.application.HarnessRunService;
import com.example.myllm.harness.application.HarnessStepService;
import com.example.myllm.harness.config.HarnessProperties;
import com.example.myllm.harness.domain.RunStatus;
import com.example.myllm.harness.domain.RunType;
import com.example.myllm.harness.entity.HarnessRun;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Harness HTTP 契约测试，不启动 Spring 容器和外部服务。 */
class HarnessRunControllerTests {

    private HarnessRunService runService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        runService = mock(HarnessRunService.class);
        HarnessRunController controller = new HarnessRunController(
                runService,
                mock(HarnessStepService.class),
                mock(HarnessEventService.class),
                new HarnessDefinitionRegistry(),
                new HarnessProperties());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReturnsAcceptedAndLocation() throws Exception {
        HarnessRun run = sampleRun();
        when(runService.createRun(any())).thenReturn(run);

        mockMvc.perform(post("/api/harness/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"objective":"查询汽车用户手册","clientRequestId":"request-001","maxSteps":6}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/harness/runs/run-001"))
                .andExpect(jsonPath("$.runId").value("run-001"))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        verify(runService).createRun(any());
    }

    @Test
    void unknownDefinitionReturnsStableDomainError() throws Exception {
        mockMvc.perform(post("/api/harness/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"objective":"test","definitionId":"unknown-definition"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private static HarnessRun sampleRun() {
        HarnessRun run = new HarnessRun();
        run.setRunId("run-001");
        run.setDefinitionId("knowledge-assistant");
        run.setDefinitionVersion(1);
        run.setDefinitionHash("hash");
        run.setRunType(RunType.AGENT_LOOP);
        run.setStatus(RunStatus.QUEUED);
        run.setMaxSteps(6);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        return run;
    }
}
