package com.dony.api.matching.events;

import java.util.UUID;

public class BidAcceptedEvent {
    private final UUID bidId;
    private final UUID senderId;
    private final UUID travelerId;
    private final UUID announcementId;

    /**
     * Vrai quand le paiement passe par un lien externe (Wave, Orange Money) : l'expéditeur
     * recevra alors une notification « Payez votre trajet » qui annonce déjà l'acceptation
     * et porte le lien. Sans ce drapeau, il recevait deux push pour la même action.
     */
    private final boolean mobileMoney;

    /** Conserve l'ancienne signature : par défaut, aucun paiement externe ne suit. */
    public BidAcceptedEvent(UUID bidId, UUID senderId, UUID travelerId, UUID announcementId) {
        this(bidId, senderId, travelerId, announcementId, false);
    }

    public BidAcceptedEvent(UUID bidId, UUID senderId, UUID travelerId, UUID announcementId,
                            boolean mobileMoney) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.travelerId = travelerId;
        this.announcementId = announcementId;
        this.mobileMoney = mobileMoney;
    }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public UUID getTravelerId() { return travelerId; }
    public UUID getAnnouncementId() { return announcementId; }
    public boolean isMobileMoney() { return mobileMoney; }
}
