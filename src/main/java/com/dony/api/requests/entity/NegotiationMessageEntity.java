package com.dony.api.requests.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Append-only entity — never UPDATE or DELETE rows in negotiation_messages.
 * Use the static factory {@link #create(UUID, UUID, NegotiationMessageKind, BigDecimal, String)}
 * to construct instances from application code instead of calling the no-arg constructor directly.
 */
@Entity
@Table(name = "negotiation_messages")
public class NegotiationMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "thread_id", nullable = false)
    private UUID threadId;

    @Column(name = "from_user_id", nullable = false)
    private UUID fromUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NegotiationMessageKind kind;

    @Column(name = "proposed_price_eur", precision = 10, scale = 2)
    private BigDecimal proposedPriceEur;

    @Column(length = 280)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    // === NO-ARG CONSTRUCTOR (required by JPA) ===

    public NegotiationMessageEntity() { /* JPA */ }

    // === STATIC FACTORY (preferred for application code) ===

    /**
     * Preferred way to construct a new message in application code.
     * Do not call setters directly on newly created instances — this entity is append-only.
     */
    public static NegotiationMessageEntity create(UUID threadId, UUID fromUserId,
                                                  NegotiationMessageKind kind,
                                                  BigDecimal proposedPriceEur,
                                                  String body) {
        NegotiationMessageEntity e = new NegotiationMessageEntity();
        e.threadId = threadId;
        e.fromUserId = fromUserId;
        e.kind = kind;
        e.proposedPriceEur = proposedPriceEur;
        e.body = body;
        return e;
    }

    // === GETTERS ===

    public UUID getId() { return id; }

    public UUID getThreadId() { return threadId; }

    public UUID getFromUserId() { return fromUserId; }

    public NegotiationMessageKind getKind() { return kind; }

    public BigDecimal getProposedPriceEur() { return proposedPriceEur; }

    public String getBody() { return body; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    // === SETTERS (needed for JPA hydration — do not call from application code) ===

    public void setId(UUID id) { this.id = id; }

    public void setThreadId(UUID threadId) { this.threadId = threadId; }

    public void setFromUserId(UUID fromUserId) { this.fromUserId = fromUserId; }

    public void setKind(NegotiationMessageKind kind) { this.kind = kind; }

    public void setProposedPriceEur(BigDecimal proposedPriceEur) { this.proposedPriceEur = proposedPriceEur; }

    public void setBody(String body) { this.body = body; }

    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
