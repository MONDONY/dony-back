package com.yadony.api.cancellation;

public enum CancellationReason {
    OTHER,
    SENDER_NO_SHOW,
    TRIP_CANCELLED,
    MUTUAL_AGREEMENT,
    SENDER_CANCEL_AFTER_HANDOVER,
    TRAVELER_CANCEL_AFTER_HANDOVER,
    /** Voyageur annule le transport d'un colis payé (bid ACCEPTED/PAYMENT_ESCROWED), sans annuler le trajet. */
    BID_CANCELLED_BY_TRAVELER,
    /** Voyageur refuse une demande déjà payée (bid PAYMENT_ESCROWED) via rejectBid. */
    BID_REJECTED_AFTER_PAYMENT
}
