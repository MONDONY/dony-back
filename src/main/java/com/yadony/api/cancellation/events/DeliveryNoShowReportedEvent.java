package com.yadony.api.cancellation.events;

import java.util.UUID;

/** Publié quand une partie signale une absence à la remise du destinataire (arrivée).
 *  Écouté par NotificationDispatcher pour notifier l'autre partie. */
public class DeliveryNoShowReportedEvent {
    private final UUID bidId;
    private final UUID senderId;
    private final UUID travelerId;
    private final boolean reportedByTraveler; // true = voyageur signale destinataire absent

    public DeliveryNoShowReportedEvent(UUID bidId, UUID senderId, UUID travelerId, boolean reportedByTraveler) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.travelerId = travelerId;
        this.reportedByTraveler = reportedByTraveler;
    }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public UUID getTravelerId() { return travelerId; }
    public boolean isReportedByTraveler() { return reportedByTraveler; }
}
