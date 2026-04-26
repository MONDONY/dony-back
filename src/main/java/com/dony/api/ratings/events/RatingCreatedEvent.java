package com.dony.api.ratings.events;

import java.util.UUID;

public class RatingCreatedEvent {

    private final UUID ratingId;
    private final UUID ratedUserId;
    private final UUID raterId;
    private final int stars;

    public RatingCreatedEvent(UUID ratingId, UUID ratedUserId, UUID raterId, int stars) {
        this.ratingId = ratingId;
        this.ratedUserId = ratedUserId;
        this.raterId = raterId;
        this.stars = stars;
    }

    public UUID getRatingId() { return ratingId; }
    public UUID getRatedUserId() { return ratedUserId; }
    public UUID getRaterId() { return raterId; }
    public int getStars() { return stars; }
}
