package com.dony.api.matching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Une photo de colis jointe à un bid. Pas de soft-delete (purge physique voulue) —
 * n'étend donc pas BaseEntity.
 */
@Entity
@Table(name = "bid_photos")
public class BidPhotoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private BidPhotoStatus status = BidPhotoStatus.ACTIVE;

    @Column(name = "deleting_since")
    private LocalDateTime deletingSince;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    protected BidPhotoEntity() {
    }

    public BidPhotoEntity(UUID bidId, String objectKey, int position) {
        this.bidId = bidId;
        this.objectKey = objectKey;
        this.position = position;
    }

    /** Idempotent : passe en DELETING et horodate la première fois. */
    public void markDeleting() {
        if (this.status != BidPhotoStatus.DELETING) {
            this.status = BidPhotoStatus.DELETING;
            this.deletingSince = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public UUID getId() { return id; }
    public UUID getBidId() { return bidId; }
    public String getObjectKey() { return objectKey; }
    public int getPosition() { return position; }
    public BidPhotoStatus getStatus() { return status; }
    public LocalDateTime getDeletingSince() { return deletingSince; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
