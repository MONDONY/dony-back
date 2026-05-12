package com.dony.api.auth.events;

import java.util.UUID;

/**
 * Published by AuthService after a new user is persisted.
 * Listened by UserRegisteredReferralListener to generate the referral code.
 */
public record UserRegisteredEvent(UUID userId, String firebaseUid) {}
