package com.dony.api.cancellation;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "rematch_suggestions")
public class RematchSuggestionEntity extends BaseEntity {

    @Column(name = "cancellation_id", nullable = false)
    private UUID cancellationId;

    @Column(name = "announcement_id", nullable = false)
    private UUID announcementId;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "SUGGESTED";

    public UUID getCancellationId() { return cancellationId; }
    public void setCancellationId(UUID cancellationId) { this.cancellationId = cancellationId; }

    public UUID getAnnouncementId() { return announcementId; }
    public void setAnnouncementId(UUID announcementId) { this.announcementId = announcementId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
