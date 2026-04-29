package com.dony.api.notifications;

import com.dony.api.auth.UserRepository;
import com.dony.api.auth.events.UserSuspendedEvent;
import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.disputes.events.DisputeOpenedEvent;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.matching.events.HandoverDefinedEvent;
import com.dony.api.matching.events.ParcelRefusedEvent;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import com.dony.api.payments.events.PaymentReleasedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central notification orchestrator. All business services must go through this class.
 * Never call FcmService or SmsService directly from outside this package.
 *
 * Critical events (PAYMENT_RELEASED, DELIVERY_CONFIRMED, DISPUTE_OPENED) are marked
 * is_critical=true so SmsFallbackScheduler sends an SMS if no ACK arrives within 60s.
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

    // ── Public API ───────────────────────────────────────────────────────────

    public void notifyUser(UUID userId, String title, String body, Map<String, String> data) {
        var saved = notificationService.persist(userId, data.getOrDefault("type", ""), title, body, data, false);
        Map<String, String> dataWithId = withNotificationId(data, saved.getId());
        fcmService.sendToUser(userId, title, body, dataWithId);
    }

    // Critical: persisted with is_critical=true → SmsFallbackScheduler sends SMS if no ACK in 60s
    private void notifyCritical(UUID userId, String title, String body, Map<String, String> data) {
        var saved = notificationService.persist(userId, data.getOrDefault("type", ""), title, body, data, true);
        Map<String, String> dataWithId = withNotificationId(data, saved.getId());
        fcmService.sendToUser(userId, title, body, dataWithId);
    }

    private static Map<String, String> withNotificationId(Map<String, String> original, UUID id) {
        var copy = new HashMap<>(original);
        copy.put("notificationId", id.toString());
        return copy;
    }

    public void notifyBySms(String phoneNumber, String message) {
        smsService.send(phoneNumber, message);
    }

    // ── Story 8.2 — Event listeners ──────────────────────────────────────────

    @EventListener @Async
    public void onBidCreated(BidCreatedEvent event) {
        String body = String.format("%s veut envoyer %.1f kg — %s",
                event.getSenderFirstName(), event.getWeightKg().doubleValue(), event.getCorridor());
        notifyUser(event.getTravelerId(), "Nouvelle demande d'envoi", body,
                Map.of("type", "BID_CREATED",
                       "bidId", event.getBidId().toString(),
                       "announcementId", event.getAnnouncementId().toString()));
    }

    @EventListener @Async
    public void onBidAccepted(BidAcceptedEvent event) {
        String name = userRepository.findById(event.getTravelerId())
                .map(u -> u.getFirstName() != null ? u.getFirstName() : "Le voyageur")
                .orElse("Le voyageur");
        notifyUser(event.getSenderId(), "Demande acceptée !",
                name + " accepte votre colis",
                Map.of("type", "BID_ACCEPTED", "bidId", event.getBidId().toString()));
    }

    @EventListener @Async
    public void onBidRejected(BidRejectedEvent event) {
        notifyUser(event.getSenderId(), "Demande refusée",
                "Le voyageur a refusé votre demande",
                Map.of("type", "BID_REJECTED", "bidId", event.getBidId().toString()));
    }

    @EventListener @Async
    public void onHandoverDefined(HandoverDefinedEvent event) {
        String dateStr = event.getWindowStart() != null
                ? event.getWindowStart().format(DATE_FMT) : "à confirmer";
        String location = event.getLocation() != null ? event.getLocation() : "lieu à confirmer";
        notifyUser(event.getSenderId(), "Point de remise défini",
                "Remise : " + location + " le " + dateStr,
                Map.of("type", "HANDOVER_DEFINED", "bidId", event.getBidId().toString()));
    }

    @EventListener @Async
    public void onTripCancelled(TripCancelledEvent event) {
        if (event.getAffectedSenderIds() == null) return;
        for (UUID senderId : event.getAffectedSenderIds()) {
            notifyUser(senderId, "Trajet annulé",
                    "Le voyageur a annulé son trajet. Remboursement en cours.",
                    Map.of("type", "TRIP_CANCELLED"));
        }
    }

    // Critical events — SMS fallback triggered by SmsFallbackScheduler after 60s without ACK

    @EventListener @Async
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        notifyCritical(event.getSenderId(), "Livraison confirmée",
                "Votre colis est arrivé à destination",
                Map.of("type", "DELIVERY_CONFIRMED", "bidId", event.getBidId().toString()));
    }

    @EventListener @Async
    public void onPaymentReleased(PaymentReleasedEvent event) {
        String amount = String.format(java.util.Locale.FRENCH, "%.2f €", event.getAmount().doubleValue());
        notifyCritical(event.getTravelerId(), "Paiement reçu !",
                amount + " — virement en cours, sous 24h",
                Map.of("type", "PAYMENT_RELEASED", "bidId", event.getBidId().toString()));
    }

    @EventListener @Async
    public void onDisputeOpened(DisputeOpenedEvent event) {
        Map<String, String> data = Map.of("type", "DISPUTE_OPENED", "bidId", event.getBidId().toString());
        notifyCritical(event.getSenderId(),  "Litige ouvert", "Un incident a été signalé sur votre envoi",  data);
        notifyCritical(event.getTravelerId(), "Litige ouvert", "Un incident a été signalé sur votre colis", data);
    }

    // Story 9.4 — Notification expéditeur : colis refusé
    @EventListener @Async
    public void onParcelRefused(ParcelRefusedEvent event) {
        String reason = event.getReason() != null ? event.getReason() : "contenu non conforme";
        notifyUser(event.getSenderId(), "Colis refusé",
                "Votre colis a été refusé par le voyageur — raison : " + reason,
                Map.of("type", "PARCEL_REFUSED", "bidId", event.getBidId().toString()));
    }

    // Story 9.6 — Notification expéditeur : voyageur no-show
    @EventListener @Async
    public void onVoyageurNoShow(VoyageurNoShowEvent event) {
        notifyUser(event.getSenderId(), "Voyageur absent",
                "Le voyageur ne s'est pas présenté à la remise. Remboursement en cours.",
                Map.of("type", "TRIP_CANCELLED", "bidId", event.getBidId().toString()));
    }

    // Story 9.5 — Notification utilisateur : compte suspendu
    @EventListener @Async
    public void onUserSuspended(UserSuspendedEvent event) {
        notifyUser(event.getUserId(), "Compte suspendu",
                "Votre compte a été suspendu suite à des incidents répétés",
                Map.of("type", "ACCOUNT_SUSPENDED"));
    }

    // Messaging — new message notification (called by MessagingNotifyController)
    public void sendMessageNotification(UUID senderId, UUID travelerId,
                                         String senderFirebaseUid, String preview,
                                         String conversationId) {
        var senderUser = userRepository.findAll().stream()
                .filter(u -> senderFirebaseUid.equals(u.getFirebaseUid()))
                .findFirst().orElse(null);

        UUID recipientId = null;
        String senderName = "Un utilisateur";
        if (senderUser != null) {
            senderName = senderUser.getFirstName() != null ? senderUser.getFirstName() : "Un utilisateur";
            recipientId = senderUser.getId().equals(senderId) ? travelerId : senderId;
        }

        if (recipientId == null) return;

        String truncated = preview.length() > 60 ? preview.substring(0, 57) + "..." : preview;
        notifyUser(recipientId, "Message de " + senderName, truncated,
                Map.of("type", "NEW_MESSAGE", "conversationId", conversationId));
    }
}