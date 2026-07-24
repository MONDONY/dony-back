package com.dony.api.auth.events;

import java.util.UUID;

/**
 * Compte suspendu. Ne transporte aucune coordonnée : le seul écouteur
 * ({@code NotificationDispatcher}) notifie par userId, et téléphone / email ne
 * sont plus stockés en base — ils vivent dans Firebase.
 */
public class UserSuspendedEvent {

    private final UUID userId;
    private final String reason;

    public UserSuspendedEvent(UUID userId, String reason) {
        this.userId = userId;
        this.reason = reason;
    }

    public UUID getUserId() { return userId; }
    public String getReason() { return reason; }
}
