package com.dony.api.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Tracks Stripe events that have already been processed to ensure idempotent webhook handling.
 * Uses the Stripe event ID (e.g. "evt_xxx") as primary key.
 */
@Entity
@Table(name = "processed_stripe_events")
public class ProcessedStripeEvent {

    @Id
    @Column(name = "event_id", length = 255, nullable = false)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedStripeEvent() {}

    public ProcessedStripeEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }

    public String getEventId() { return eventId; }
    public Instant getProcessedAt() { return processedAt; }
}
