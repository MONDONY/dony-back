package com.dony.api.common.stripe;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "stripe_event_inbox")
public class StripeEventInbox {

    @Id
    @Column(name = "event_id", length = 255, nullable = false)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private StripeWebhookSource source;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private StripeEventStatus status = StripeEventStatus.RECEIVED;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected StripeEventInbox() {}

    public StripeEventInbox(String eventId, StripeWebhookSource source,
                             String eventType, String payload) {
        this.eventId = eventId;
        this.source = source;
        this.eventType = eventType;
        this.payload = payload;
        Instant now = Instant.now();
        this.receivedAt = now;
        this.nextAttemptAt = now;
    }

    public String getEventId() { return eventId; }
    public StripeWebhookSource getSource() { return source; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public StripeEventStatus getStatus() { return status; }
    public void setStatus(StripeEventStatus status) { this.status = status; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
