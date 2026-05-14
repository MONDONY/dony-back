package com.dony.api.notifications;

import com.dony.api.requests.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RequestEventsListener {

    private static final Logger log = LoggerFactory.getLogger(RequestEventsListener.class);

    private final NotificationDispatcher dispatcher;

    public RequestEventsListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @EventListener
    @Async
    public void onPackageRequestCreated(PackageRequestCreatedEvent e) {
        // TODO(matching-travelers): pull travelers with announcement matching corridor + date,
        //   send batch FCM. For now, just audit-log the creation. The notify-traveler logic
        //   is a separate feature requiring a corridor-matching query.
        log.info("PackageRequestCreated event: requestId={} corridor={}->{}",
            e.requestId(), e.departureCity(), e.arrivalCity());
    }

    @EventListener
    @Async
    public void onNegotiationStarted(NegotiationStartedEvent e) {
        dispatcher.notifyUser(
            e.senderId(),
            "Nouvelle proposition reçue",
            String.format("Un voyageur propose %.2f€ pour votre demande", e.proposedPriceEur()),
            Map.of(
                "type", "negotiation_started",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    @EventListener
    @Async
    public void onNegotiationCounterPosted(NegotiationCounterPostedEvent e) {
        dispatcher.notifyUser(
            e.toUserId(),
            "Nouvelle contre-proposition",
            String.format("Nouvelle offre: %.2f€ (round %d)", e.newPriceEur(), e.roundsCount()),
            Map.of(
                "type", "negotiation_counter",
                "threadId", e.threadId().toString(),
                "messageId", e.messageId().toString()
            )
        );
    }

    /**
     * Sender just clicked "Accepter le prix". Status is now AWAITING_TRIP.
     * Notify the traveler that they need to link a trip.
     */
    @EventListener
    @Async
    public void onNegotiationAwaitingTrip(NegotiationAwaitingTripEvent e) {
        dispatcher.notifyUser(
            e.travelerId(),
            "Votre offre a été acceptée 🎉",
            String.format("L'expéditeur a accepté à %.0f€. Choisissez maintenant le trajet à lier.",
                e.agreedPriceEur()),
            Map.of(
                "type", "negotiation_awaiting_trip",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    /**
     * Traveler just linked a trip. Status is now AWAITING_PAYMENT.
     * Notify the sender that they need to pay.
     */
    @EventListener
    @Async
    public void onNegotiationAwaitingPayment(NegotiationAwaitingPaymentEvent e) {
        dispatcher.notifyUser(
            e.senderId(),
            "Trajet validé — paiement requis 💳",
            String.format("Le voyageur a confirmé son trajet. Payez %.0f€ pour finaliser la commande.",
                e.agreedPriceEur()),
            Map.of(
                "type", "negotiation_awaiting_payment",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    /**
     * Final ACCEPTED state — fires only after payment is captured (escrow active).
     * Notify both parties that the deal is sealed.
     */
    @EventListener
    @Async
    public void onPackageRequestAccepted(PackageRequestAcceptedEvent e) {
        dispatcher.notifyUser(
            e.travelerId(),
            "Paiement reçu — c'est parti ✈️",
            String.format("Le paiement de %.0f€ est en escrow. Vous pouvez préparer le retrait du colis.",
                e.agreedPriceEur()),
            Map.of(
                "type", "request_accepted",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
        dispatcher.notifyUser(
            e.senderId(),
            "Demande finalisée ✅",
            String.format("Paiement de %.0f€ confirmé. Le voyageur va vous contacter.",
                e.agreedPriceEur()),
            Map.of(
                "type", "request_accepted",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
    }

    @EventListener
    @Async
    public void onPackageRequestExpired(PackageRequestExpiredEvent e) {
        dispatcher.notifyUser(
            e.senderId(),
            "Votre demande a expiré",
            "Aucun voyageur n'a accepté votre demande dans les délais. Vous pouvez en créer une nouvelle.",
            Map.of(
                "type", "request_expired",
                "packageRequestId", e.requestId().toString()
            )
        );
    }

    @EventListener
    @Async
    public void onNegotiationExpired(NegotiationExpiredEvent e) {
        // Notify traveler
        dispatcher.notifyUser(
            e.travelerId(),
            "Négociation expirée",
            "Cette négociation a expiré faute d'activité.",
            Map.of(
                "type", "negotiation_expired",
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString()
            )
        );
        // senderId may be null — only notify if present
        // (event currently passes null for senderId per scheduler; can be enriched later)
        if (e.senderId() != null) {
            dispatcher.notifyUser(
                e.senderId(),
                "Négociation expirée",
                "Cette négociation a expiré faute d'activité.",
                Map.of(
                    "type", "negotiation_expired",
                    "threadId", e.threadId().toString(),
                    "packageRequestId", e.packageRequestId().toString()
                )
            );
        }
    }
}
