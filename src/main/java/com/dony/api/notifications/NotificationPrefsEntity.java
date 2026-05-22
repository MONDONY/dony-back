package com.dony.api.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "user_notification_preferences")
public class NotificationPrefsEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "push_activity_bids", nullable = false)
    private boolean pushActivityBids = true;

    @Column(name = "push_activity_negotiations", nullable = false)
    private boolean pushActivityNegotiations = true;

    @Column(name = "push_messages", nullable = false)
    private boolean pushMessages = true;

    @Column(name = "push_trip_reminder", nullable = false)
    private boolean pushTripReminder = true;

    @Column(name = "push_promo", nullable = false)
    private boolean pushPromo = false;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public boolean isPushActivityBids() { return pushActivityBids; }
    public void setPushActivityBids(boolean v) { this.pushActivityBids = v; }
    public boolean isPushActivityNegotiations() { return pushActivityNegotiations; }
    public void setPushActivityNegotiations(boolean v) { this.pushActivityNegotiations = v; }
    public boolean isPushMessages() { return pushMessages; }
    public void setPushMessages(boolean v) { this.pushMessages = v; }
    public boolean isPushTripReminder() { return pushTripReminder; }
    public void setPushTripReminder(boolean v) { this.pushTripReminder = v; }
    public boolean isPushPromo() { return pushPromo; }
    public void setPushPromo(boolean v) { this.pushPromo = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
