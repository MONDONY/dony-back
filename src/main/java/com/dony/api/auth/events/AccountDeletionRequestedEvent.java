package com.dony.api.auth.events;

import java.util.UUID;

public class AccountDeletionRequestedEvent {

    private final UUID userId;

    public AccountDeletionRequestedEvent(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() { return userId; }
}
