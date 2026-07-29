package com.yadony.api.kyc.events;

import java.util.UUID;

/**
 * KYC vérifié. Ne transporte aucune coordonnée : le seul écouteur
 * ({@code BusinessMetricsListener}) ne compte que l'événement, et le téléphone
 * n'est plus stocké en base — il vit dans Firebase.
 */
public class UserKycVerifiedEvent {

    private final UUID userId;

    public UserKycVerifiedEvent(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() { return userId; }
}
