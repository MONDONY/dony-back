package com.dony.api.addressbook.favorite;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

@Entity
@Table(name = "favorite_travelers")
@Where(clause = "deleted_at IS NULL")
public class FavoriteTravelerEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
