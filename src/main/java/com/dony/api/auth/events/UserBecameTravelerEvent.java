package com.dony.api.auth.events;

import java.util.UUID;

public class UserBecameTravelerEvent {

    private final UUID userId;

    public UserBecameTravelerEvent(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }
}
