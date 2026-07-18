package com.dony.api.cancellation.events;

import java.util.UUID;

/**
 * Publié par {@code BidLostRematchListener} après qu'une {@code CancellationEntity} et
 * d'éventuelles suggestions rematch aient été créées suite à un {@code BidRejectedEvent}
 * éligible (annulation/refus voyageur d'un bid payé, hors annulation de trajet).
 *
 * <p>Consommé par {@code NotificationDispatcher} pour envoyer une notification unique
 * enrichie (deep link rematch si {@code suggestionCount > 0}) et sauter la notification
 * {@code BID_REJECTED} générique.
 */
public record BidLostRematchPreparedEvent(
        UUID senderId,
        UUID bidId,
        UUID cancellationId,
        int suggestionCount,
        boolean cancelledByTraveler) {
}
