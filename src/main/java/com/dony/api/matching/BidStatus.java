package com.dony.api.matching;

import java.util.EnumSet;
import java.util.Set;

public enum BidStatus {
    AWAITING_PAYMENT,
    PENDING,
    PAYMENT_ESCROWED,
    ACCEPTED,
    HANDED_OVER,
    IN_TRANSIT,
    REJECTED,
    CANCELLED,
    COMPLETED,
    NO_SHOW,
    PARCEL_REFUSED,
    EXPIRED;

    /**
     * Bids que le voyageur a effectivement acceptés — le statut a dépassé le
     * stade PENDING/PAYMENT_ESCROWED, quelle que soit la suite (remise, transit,
     * livraison, no-show, refus du colis). Sert au taux d'acceptation, pour ne
     * pas ignorer les bids acceptés puis livrés (qui ne sont plus en ACCEPTED).
     */
    public static final Set<BidStatus> ACCEPTED_OR_BEYOND = EnumSet.of(
            ACCEPTED, HANDED_OVER, IN_TRANSIT, COMPLETED, NO_SHOW, PARCEL_REFUSED);
}
