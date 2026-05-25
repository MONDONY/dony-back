package com.dony.api.subscriptions;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

@Entity
@Table(name = "traveler_subscriptions")
@Where(clause = "deleted_at IS NULL")
public class TravelerSubscriptionEntity extends BaseEntity {

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled = false;

    @Column(name = "has_new", nullable = false)
    private boolean hasNew = false;

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public boolean isPushEnabled() { return pushEnabled; }
    public void setPushEnabled(boolean pushEnabled) { this.pushEnabled = pushEnabled; }

    public boolean isHasNew() { return hasNew; }
    public void setHasNew(boolean hasNew) { this.hasNew = hasNew; }
}
