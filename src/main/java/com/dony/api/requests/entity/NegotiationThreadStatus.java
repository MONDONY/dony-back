package com.dony.api.requests.entity;

public enum NegotiationThreadStatus {
    /** En cours de négociation (proposal / counter). */
    OPEN,
    /** Sender a accepté le prix. Le traveler doit lier (ou créer) un trajet. */
    AWAITING_TRIP,
    /** Traveler a lié un trajet. Le sender doit payer en escrow. */
    AWAITING_PAYMENT,
    /** Paiement confirmé. Thread finalisé. Les threads concurrents passent à AUTO_REJECTED. */
    ACCEPTED,
    /** Rejet manuel par un participant. */
    REJECTED,
    /** Rejet auto : un thread concurrent sur la même demande a été ACCEPTED. */
    AUTO_REJECTED,
    /** Expiré faute d'activité. */
    EXPIRED
}
