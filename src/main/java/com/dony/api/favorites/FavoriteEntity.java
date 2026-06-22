package com.dony.api.favorites;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

@Entity
@Table(name = "favorites")
@Where(clause = "deleted_at IS NULL")
public class FavoriteEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private FavoriteTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    protected FavoriteEntity() {}

    public FavoriteEntity(UUID userId, FavoriteTargetType targetType, UUID targetId) {
        this.userId = userId;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    public UUID getUserId() { return userId; }
    public FavoriteTargetType getTargetType() { return targetType; }
    public UUID getTargetId() { return targetId; }

    /** Réactive une ligne précédemment soft-deleted. */
    public void revive() { setDeletedAt(null); }
}
