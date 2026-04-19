package com.dony.api.kyc.events;

import java.util.UUID;

public class UserKycVerifiedEvent {

    private final UUID userId;
    private final String phoneNumber;

    public UserKycVerifiedEvent(UUID userId, String phoneNumber) {
        this.userId = userId;
        this.phoneNumber = phoneNumber;
    }

    public UUID getUserId() { return userId; }
    public String getPhoneNumber() { return phoneNumber; }
}
