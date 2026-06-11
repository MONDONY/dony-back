package com.dony.api.cancellation.events;

import java.util.UUID;

/**
 * Publié quand l'expéditeur signale manuellement un voyageur absent (no-show).
 * Écouté par {@code matching/TravelerNoShowReportListener} qui délègue au
 * {@code NoShowService} pour marquer le bid NO_SHOW (remboursement escrow).
 */
public class TravelerNoShowReportedEvent {

    private final UUID bidId;
    private final UUID senderId;

    public TravelerNoShowReportedEvent(UUID bidId, UUID senderId) {
        this.bidId = bidId;
        this.senderId = senderId;
    }

    public UUID getBidId() { return bidId; }

    public UUID getSenderId() { return senderId; }
}
