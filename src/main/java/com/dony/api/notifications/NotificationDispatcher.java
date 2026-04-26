package com.dony.api.notifications;

import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.disputes.events.DisputeOpenedEvent;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.matching.events.HandoverDefinedEvent;
import com.dony.api.payments.events.PaymentReleasedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Central notification dispatcher. All business services must go through this
 * class — never call FcmService or SmsService directly from outside this package.
 *
 * For critical events (payment, delivery, dispute), the SMS fallback timer
 * is handled by Story 8.3.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final FcmService fcmService;
    private final SmsService smsService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public NotificationDispatcher(FcmService fcmService, SmsService smsService,
                                  UserRepository userRepository,
                                  NotificationService notificationService) {
        this.fcmService = fcmService;
        this.smsService = smsService;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    // ── Public API (for direct calls from within the package) ─────────────────

    public void notifyUser(UUID userId, String title, String body, Map<String, String> data) {
        notificationService.persist(userId, data.getOrDefault("type", ""), title, body, data);
        boolean sent = fcmService.sendToUser(userId, title, body, data);
        if (!sent) {
            log.debug("[Dispatcher] FCM not sent for user={} (no token or error)", userId);
        }
    }

    public void notifyBySms(String phoneNumber, String message) {
        smsService.send(phoneNumber, message);
    }

    // ── Story 8.2 — Event listeners ──────────────────────────────────────────

    @EventListener
    @Async
    public void onBidCreated(BidCreatedEvent event) {
        String body = String.format("%s veut envoyer %.1f kg — %s",
                event.getSenderFirstName(), event.getWeightKg().doubleValue(), event.getCorridor());
        notifyUser(event.getTravelerId(),
                "Nouvelle demande d'envoi",
                body,
                Map.of("type", "BID_CREATED",
                        "bidId", event.getBidId().toString(),
                        "announcementId", event.getBidId().toString()));
    }

    @EventListener
    @Async
    public void onBidAccepted(BidAcceptedEvent event) {
        String travelerName = userRepository.findById(event.getTravelerId())
                .map(u -> u.getFirstName() != null ? u.getFirstName() : "Le voyageur")
                .orElse("Le voyageur");
        notifyUser(event.getSenderId(),
                "Demande acceptée !",
                travelerName + " accepte votre colis",
                Map.of("type", "BID_ACCEPTED",
                        "bidId", event.getBidId().toString()));
    }

    @EventListener
    @Async
    public void onBidRejected(BidRejectedEvent event) {
        notifyUser(event.getSenderId(),
                "Demande refusée",
                "Le voyageur a refusé votre demande",
                Map.of("type", "BID_REJECTED",
                        "bidId", event.getBidId().toString()));
    }

    @EventListener
    @Async
    public void onHandoverDefined(HandoverDefinedEvent event) {
        String dateStr = event.getWindowStart() != null
                ? event.getWindowStart().format(DATE_FMT) : "à confirmer";
        String location = event.getLocation() != null ? event.getLocation() : "lieu à confirmer";
        notifyUser(event.getSenderId(),
                "Point de remise défini",
                "Remise : " + location + " le " + dateStr,
                Map.of("type", "HANDOVER_DEFINED",
                        "bidId", event.getBidId().toString()));
    }

    @EventListener
    @Async
    public void onTripCancelled(TripCancelledEvent event) {
        if (event.getAffectedSenderIds() == null) return;
        for (UUID senderId : event.getAffectedSenderIds()) {
            notifyUser(senderId,
                    "Trajet annulé",
                    "Le voyageur a annulé son trajet. Remboursement en cours.",
                    Map.of("type", "TRIP_CANCELLED"));
        }
    }

    @EventListener
    @Async
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        notifyUser(event.getSenderId(),
                "Livraison confirmée",
                "Votre colis est arrivé à destination",
                Map.of("type", "DELIVERY_CONFIRMED",
                        "bidId", event.getBidId().toString()));
    }

    @EventListener
    @Async
    public void onPaymentReleased(PaymentReleasedEvent event) {
        String amountStr = String.format(java.util.Locale.FRENCH, "%.2f €", event.getAmount().doubleValue());
        notifyUser(event.getTravelerId(),
                "Paiement reçu !",
                amountStr + " — virement en cours, sous 24h",
                Map.of("type", "PAYMENT_RELEASED",
                        "bidId", event.getBidId().toString()));
    }

    @EventListener
    @Async
    public void onDisputeOpened(DisputeOpenedEvent event) {
        Map<String, String> data = Map.of("type", "DISPUTE_OPENED",
                "bidId", event.getBidId().toString());
        notifyUser(event.getSenderId(),  "Litige ouvert", "Un incident a été signalé sur votre envoi", data);
        notifyUser(event.getTravelerId(), "Litige ouvert", "Un incident a été signalé sur votre colis", data);
    }
}
