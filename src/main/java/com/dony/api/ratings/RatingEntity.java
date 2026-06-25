package com.dony.api.ratings;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

@Entity
@Table(name = "ratings")
@Where(clause = "deleted_at IS NULL")
public class RatingEntity extends BaseEntity {

    @Column(name = "rater_id")
    private UUID raterId;

    @Column(name = "rated_user_id", nullable = false)
    private UUID ratedUserId;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "tracking_token", length = 36)
    private String trackingToken;

    @Column(name = "stars", nullable = false)
    private int stars;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "excluded_from_average", nullable = false)
    private boolean excludedFromAverage = false;

    @Column(name = "flagged", nullable = false)
    private boolean flagged = false;

    public UUID getRaterId() { return raterId; }
    public void setRaterId(UUID raterId) { this.raterId = raterId; }

    public UUID getRatedUserId() { return ratedUserId; }
    public void setRatedUserId(UUID ratedUserId) { this.ratedUserId = ratedUserId; }

    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }

    public String getTrackingToken() { return trackingToken; }
    public void setTrackingToken(String trackingToken) { this.trackingToken = trackingToken; }

    public int getStars() { return stars; }
    public void setStars(int stars) { this.stars = stars; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public boolean isExcludedFromAverage() { return excludedFromAverage; }
    public void setExcludedFromAverage(boolean excludedFromAverage) { this.excludedFromAverage = excludedFromAverage; }

    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean flagged) { this.flagged = flagged; }
}
