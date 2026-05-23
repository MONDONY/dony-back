package com.dony.api.auth;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_blocks")
public class UserBlockEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "blocker_id", nullable = false, updatable = false)
    private UUID blockerId;

    @Column(name = "blocked_id", nullable = false, updatable = false)
    private UUID blockedId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBlockerId() { return blockerId; }
    public void setBlockerId(UUID v) { this.blockerId = v; }
    public UUID getBlockedId() { return blockedId; }
    public void setBlockedId(UUID v) { this.blockedId = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
