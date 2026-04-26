package com.dony.api.auth.events;

import java.util.UUID;

public class UserSuspendedEvent {

    private final UUID userId;
    private final String phoneNumber;
    private final String email;
    private final String reason;

    public UserSuspendedEvent(UUID userId, String phoneNumber, String email, String reason) {
        this.userId = userId;
        this.phoneNumber = phoneNumber;
        this.email = email;
        this.reason = reason;
    }

    public UUID getUserId() { return userId; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getEmail() { return email; }
    public String getReason() { return reason; }
}
