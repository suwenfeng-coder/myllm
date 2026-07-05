package com.example.myllm.harness.application;

import com.example.myllm.harness.domain.HarnessDomainException;
import com.example.myllm.harness.domain.HarnessErrorCode;
import com.example.myllm.harness.entity.HarnessEvent;
import com.example.myllm.harness.repository.HarnessEventRepository;
import com.example.myllm.harness.repository.HarnessRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Harness 运行事件追加（独立 Bean，避免 {@code @Transactional} 自调用）。 */
@Service
public class HarnessEventService {

    static final String RUN_CREATED = "RUN_CREATED";
    static final String RUN_CLAIMED = "RUN_CLAIMED";
    static final String RUN_CANCEL_REQUESTED = "RUN_CANCEL_REQUESTED";
    static final String RUN_CANCELLED = "RUN_CANCELLED";
    static final String RUN_SUCCEEDED = "RUN_SUCCEEDED";
    static final String RUN_FAILED = "RUN_FAILED";
    static final String TOOL_SUCCEEDED = "TOOL_SUCCEEDED";
    static final String TOOL_FAILED = "TOOL_FAILED";
    static final String ACTION_PARSED = "ACTION_PARSED";
    static final String MODEL_REQUESTED = "MODEL_REQUESTED";
    static final String MODEL_RESPONDED = "MODEL_RESPONDED";
    static final String CONTEXT_TRUNCATED = "CONTEXT_TRUNCATED";
    static final String VALIDATION_SUCCEEDED = "VALIDATION_SUCCEEDED";
    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String REPAIR_REQUESTED = "REPAIR_REQUESTED";

    private final HarnessRunRepository runRepository;
    private final HarnessEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public HarnessEventService(
            HarnessRunRepository runRepository,
            HarnessEventRepository eventRepository,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public HarnessEvent appendEvent(String runId, String eventType, String payloadRedactedJson) {
        lockRun(runId);
        HarnessEvent event = new HarnessEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setRunId(runId);
        event.setEventType(requireText(eventType, "eventType"));
        event.setSequenceNo(eventRepository.findMaxSequenceNo(runId) + 1);
        event.setPayloadRedactedJson(trimToNull(payloadRedactedJson));
        return eventRepository.save(event);
    }

    /** 按事件序号返回运行时间线；payload 已在写入前脱敏。 */
    @Transactional(readOnly = true)
    public java.util.List<HarnessEvent> listEvents(String runId) {
        requireRun(runId);
        return eventRepository.findByRunIdOrderBySequenceNoAsc(runId);
    }

    String payloadJson(Map<String, String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件 payload 序列化失败", e);
        }
    }

    private void requireRun(String runId) {
        if (runRepository.findById(runId).isEmpty()) {
            throw new HarnessDomainException(HarnessErrorCode.RUN_NOT_FOUND, "run 不存在: " + runId);
        }
    }

    private void lockRun(String runId) {
        runRepository.lockById(runId)
                .orElseThrow(() -> new HarnessDomainException(
                        HarnessErrorCode.RUN_NOT_FOUND, "run 不存在: " + runId));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
