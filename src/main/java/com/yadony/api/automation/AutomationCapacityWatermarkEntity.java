package com.yadony.api.automation;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "automation_capacity_watermarks")
public class AutomationCapacityWatermarkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "announcement_id", nullable = false, unique = true)
    private UUID announcementId;

    @Column(name = "free_since")
    private OffsetDateTime freeSince;

    @Column(name = "last_alerted_at")
    private OffsetDateTime lastAlertedAt;

    public AutomationCapacityWatermarkEntity() {}

    public UUID getId() { return id; }
    public UUID getAnnouncementId() { return announcementId; }
    public void setAnnouncementId(UUID announcementId) { this.announcementId = announcementId; }
    public OffsetDateTime getFreeSince() { return freeSince; }
    public void setFreeSince(OffsetDateTime freeSince) { this.freeSince = freeSince; }
    public OffsetDateTime getLastAlertedAt() { return lastAlertedAt; }
    public void setLastAlertedAt(OffsetDateTime lastAlertedAt) { this.lastAlertedAt = lastAlertedAt; }
}
