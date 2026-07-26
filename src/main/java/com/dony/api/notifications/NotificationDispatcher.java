package com.dony.api.notifications;

import com.dony.api.auth.UserRepository;
import com.dony.api.auth.events.UserSuspendedEvent;
import com.dony.api.cancellation.events.BidLostRematchPreparedEvent;
import com.dony.api.cancellation.events.DeliveryNoShowReportedEvent;
import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.disputes.events.DisputeOpenedEvent;
import com.dony.api.matching.events.AnnouncementInProgressEvent;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.matching.events.BidExpiredOnDepartureEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.matching.events.ParcelRefusedEvent;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import com.dony.api.payments.events.PaymentReleasedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
    private static final int MESSAGE_PREVIEW_MAX_LENGTH = 60;

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
        notifyUser(userId, title, body, data, true);
    }

    public void notifyUser(UUID userId, String title, String body, Map<String, String> data, boolean push) {
        var saved = notificationService.persist(userId, data.getOrDefault("type", ""), title, body, data, false);
        if (push) {
            Map<String, String> dataWithId = withNotificationId(data, saved.getId());
            fcmService.sendToUser(userId, title, body, dataWithId);
        }
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
        String body = event.getWeightKg() != null
                ? String.format("%s veut envoyer %.1f kg — %s",
                        event.getSenderFirstName(), event.getWeightKg().doubleValue(), event.getCorridor())
                : String.format("%s a une demande d'envoi — %s",
                        event.getSenderFirstName(), event.getCorridor());
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
        // Paiement par lien externe : MobileMoneyBidAcceptedListener envoie « Payez votre
        // trajet », qui annonce déjà l'acceptation ET porte le lien de paiement. Pousser en
        // plus « Demande acceptée ! » ferait deux push pour la même action, le second
        // répétant le premier. On persiste quand même la trace pour la boîte de réception.
        notifyUser(event.getSenderId(), "Demande acceptée !",
                name + " accepte votre colis",
                Map.of("type", "BID_ACCEPTED", "bidId", event.getBidId().toString()),
                !event.isMobileMoney());
    }

    @EventListener @Async
    public void onBidRejected(BidRejectedEvent event) {
        if (event.isRematchEligible()) return; // relayé par onBidLostRematchPrepared (X2/X3)
        notifyUser(event.getSenderId(), "Demande refusée",
                "Le voyageur a refusé votre demande",
                Map.of("type", "BID_REJECTED", "bidId", event.getBidId().toString()));
    }

    // Notification unique (BID_REJECTED conservé) pour un bid perdu par annulation/refus voyageur,
    // avec deep link rematch si des suggestions existent. Même pattern AFTER_COMMIT + @Async que
    // onTripCancelled : le deep link cancellationId ne doit jamais partir avant que
    // BidLostRematchListener (cancellation/) ait commité la CancellationEntity + les suggestions.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBidLostRematchPrepared(BidLostRematchPreparedEvent event) {
        String title = event.cancelledByTraveler() ? "Transport annulé" : "Demande refusée";
        String prefix = event.cancelledByTraveler()
                ? "Le voyageur a annulé le transport de votre colis"
                : "Le voyageur a refusé votre demande";
        int n = event.suggestionCount();
        // Défense : count > 0 avec cancellationId null ne devrait pas arriver (contrat X2 garantit
        // cancellationId non-null dès que suggestionCount > 0), mais si ça survient on retombe
        // sur le corps "remboursement en cours" sans deep link plutôt que de risquer un NPE.
        if (n > 0 && event.cancellationId() != null) {
            notifyUser(event.senderId(), title,
                    prefix + " — remboursement en cours. " + n
                            + " voyageur" + (n > 1 ? "s" : "") + " alternatif" + (n > 1 ? "s" : "")
                            + " disponible" + (n > 1 ? "s" : ""),
                    Map.of("type", "BID_REJECTED",
                           "bidId", event.bidId().toString(),
                           "cancellationId", event.cancellationId().toString()));
        } else {
            notifyUser(event.senderId(), title,
                    prefix + " — votre remboursement est en cours",
                    Map.of("type", "BID_REJECTED", "bidId", event.bidId().toString()));
        }
    }

    // Le deep link cancellationId ne doit pas partir avant le commit de cancelTrip
    // (rollback → push mensonger ; race → 404 sur GET /cancellations/{id}/rematch-suggestions).
    // Pattern reproduit de TripCancelledEventListener (payments) : AFTER_COMMIT + @Async, sans
    // @Transactional(REQUIRES_NEW) — ce listener ne fait que lire/notifier, pas de refund à isoler.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTripCancelled(TripCancelledEvent event) {
        if (event.getAffectedSenderIds() == null) return;
        for (UUID senderId : event.getAffectedSenderIds()) {
            TripCancelledEvent.RematchBySenderInfo info = event.getRematchBySender().get(senderId);
            if (info == null) {
                notifyUser(senderId, "Trajet annulé",
                        "Le voyageur a annulé son trajet. Remboursement en cours.",
                        Map.of("type", "TRIP_CANCELLED"));
            } else if (info.suggestionCount() > 0) {
                int n = info.suggestionCount();
                notifyUser(senderId, "Trajet annulé",
                        "Trajet annulé — remboursement en cours. "
                                + n + " voyageur" + (n > 1 ? "s" : "") + " alternatif"
                                + (n > 1 ? "s" : "") + " disponible" + (n > 1 ? "s" : ""),
                        Map.of("type", "TRIP_CANCELLED",
                               "cancellationId", info.cancellationId().toString()));
            } else {
                notifyUser(senderId, "Trajet annulé",
                        "Trajet annulé — Aucun voyageur disponible dans les 72h, votre remboursement est traité",
                        Map.of("type", "TRIP_CANCELLED"));
            }
        }
    }

    @EventListener @Async
    public void onDeliveryNoShowReported(DeliveryNoShowReportedEvent event) {
        Map<String, String> data = Map.of("type", "DELIVERY_NOSHOW_REPORTED", "bidId", event.getBidId().toString());
        if (event.isReportedByTraveler()) {
            notifyUser(event.getSenderId(), "Absence signalée à la livraison",
                    "Le voyageur signale que votre destinataire ne s'est pas présenté", data);
        } else {
            notifyUser(event.getTravelerId(), "Absence signalée à la livraison",
                    "L'expéditeur signale que vous n'avez pas livré le colis", data);
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

    // Trajet en cours — notif voyageur "Bon voyage"
    //
    // In-app seulement : aucune action n'est demandée et rien n'est annoncé que le voyageur
    // ignore — il sait qu'il part, c'est lui qui a saisi la date. Le rappel de scanner les QR
    // codes garde son utilité dans la boîte de réception, mais ne justifie pas d'interrompre.
    @EventListener @Async
    public void onAnnouncementInProgress(AnnouncementInProgressEvent event) {
        notifyUser(event.getTravelerId(), "Bon voyage !",
                "N'oublie pas de scanner les QR codes à la remise et à la livraison.",
                Map.of("type", "TRIP_IN_PROGRESS",
                       "announcementId", event.getAnnouncementId().toString()),
                false);
    }

    // Bid expiré au départ — notif expéditeur "Demande expirée"
    @EventListener @Async
    public void onBidExpiredOnDeparture(BidExpiredOnDepartureEvent event) {
        notifyUser(event.getSenderId(), "Demande expirée",
                "Le voyageur est parti avant d'avoir accepté votre demande. Remboursement en cours.",
                Map.of("type", "BID_EXPIRED",
                       "bidId", event.getBidId().toString()));
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
        var senderUser = userRepository.findByFirebaseUid(senderFirebaseUid).orElse(null);

        if (senderUser == null) {
            log.warn("sendMessageNotification: unknown senderFirebaseUid={}", senderFirebaseUid);
            return;
        }

        String senderName = senderUser.getFirstName() != null ? senderUser.getFirstName() : "Un utilisateur";
        UUID recipientId = senderUser.getId().equals(senderId) ? travelerId : senderId;

        String truncated = preview.length() > MESSAGE_PREVIEW_MAX_LENGTH
                ? preview.substring(0, MESSAGE_PREVIEW_MAX_LENGTH - 3) + "..."
                : preview;
        notifyUser(recipientId, "Message de " + senderName, truncated,
                Map.of("type", "NEW_MESSAGE", "conversationId", conversationId));
    }

    public void sendCardExpiringNotice(com.dony.api.auth.UserEntity user) {
        String brand = user.getCommissionCardBrand() != null ? user.getCommissionCardBrand() : "Carte";
        String last4 = user.getCommissionCardLast4() != null ? user.getCommissionCardLast4() : "****";
        notifyUser(user.getId(),
                "Votre carte de débit expire bientôt",
                brand + " se terminant par " + last4 + " expire ce mois-ci. " +
                "Mettez-la à jour pour continuer à accepter des paiements cash.",
                Map.of("type", "CARD_EXPIRING"));
    }
}