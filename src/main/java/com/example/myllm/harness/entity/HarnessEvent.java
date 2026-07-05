package com.example.myllm.harness.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Comment;

/** Harness 事件流，供 SSE 与时间线回放。 */
@Entity
@Table(
        name = "harness_event",
        uniqueConstraints = @UniqueConstraint(name = "uq_harness_event_run_seq", columnNames = {"run_id", "sequence_no"}),
        indexes = @Index(name = "idx_harness_event_run", columnList = "run_id"))
@Comment("Harness事件流")
public class HarnessEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload_redacted_json", columnDefinition = "TEXT")
    private String payloadRedactedJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneId.systemDefault());
        }
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(int sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayloadRedactedJson() {
        return payloadRedactedJson;
    }

    public void setPayloadRedactedJson(String payloadRedactedJson) {
        this.payloadRedactedJson = payloadRedactedJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
