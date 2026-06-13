package com.dony.api.cancellation;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.dto.CancellationRequest;
import com.dony.api.cancellation.dto.CancellationResponse;
import com.dony.api.cancellation.dto.RematchSuggestionDto;
import com.dony.api.cancellation.events.CancellationConfirmedEvent;
import com.dony.api.disputes.events.DisputeOpenedEvent;
import com.dony.api.cancellation.dto.ReturnCodeResponse;
import com.dony.api.cancellation.events.ParcelReturnedEvent;
import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.cancellation.events.TravelerHighCancellationEvent;
import com.dony.api.cancellation.events.TravelerNoShowReportedEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.matching.CapacityUnit;
import java.security.SecureRandom;
import com.dony.api.payments.cash.CommissionProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CancellationService {

    private final CancellationRepository cancellationRepository;
    private final RematchSuggestionRepository rematchSuggestionRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final CommissionProperties commissionProperties;

    private static final SecureRandom RETURN_CODE_RANDOM = new SecureRandom();
    private static final int MAX_RETURN_CODE_ATTEMPTS = 3;

    public CancellationService(CancellationRepository cancellationRepository,
                                RematchSuggestionRepository rematchSuggestionRepository,
                                BidRepository bidRepository,
                                AnnouncementRepository announcementRepository,
                                UserRepository userRepository,
                                AuditService auditService,
                                ApplicationEventPublisher eventPublisher,
                                CommissionProperties commissionProperties) {
        this.cancellationRepository = cancellationRepository;
        this.rematchSuggestionRepository = rematchSuggestionRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.commissionProperties = commissionProperties;
    }

    @Transactional
    public CancellationResponse cancelTrip(String firebaseUid, CancellationRequest request) {
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        AnnouncementEntity announcement = announcementRepository.findById(request.announcementId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Not Found", "Annonce introuvable"));

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à annuler cette annonce");
        }

        if (announcement.getStatus() == AnnouncementStatus.CANCELLED) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "already-cancelled", "Already Cancelled",
                    "Ce trajet est déjà annulé");
        }
        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "invalid-status", "Invalid Status",
                    "Seul un trajet ACTIVE peut être annulé");
        }

        // Cancel the announcement
        announcement.setStatus(AnnouncementStatus.CANCELLED);
        announcementRepository.save(announcement);

        // Cancel ALL in-progress bids on this trip (not just ACCEPTED) so each
        // sender's bid reflects the cancelled trip — sinon un bid PENDING /
        // PAYMENT_ESCROWED gardait son statut partout. Set « actif » canonique
        // (cf. BidCheckoutService). Les remboursements sont gérés par les
        // listeners de TripCancelledEvent, par bid, selon le statut du PAIEMENT :
        // un PAYMENT_ESCROWED (escrow détenu) est remboursé, un PENDING cash
        // (sans paiement) est simplement annulé.
        List<BidEntity> affectedBids = bidRepository.findByAnnouncementIdAndStatusIn(
                request.announcementId(),
                List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED));

        List<UUID> affectedSenderIds = new ArrayList<>();
        List<UUID> affectedBidIds = new ArrayList<>();
        List<CancellationEntity> cancellations = new ArrayList<>();

        for (BidEntity bid : affectedBids) {
            bid.setStatus(BidStatus.CANCELLED);
            bidRepository.save(bid);

            CancellationEntity cancellation = new CancellationEntity();
            cancellation.setBidId(bid.getId());
            cancellation.setCancelledBy(traveler.getId());
            cancellation.setReason(request.reason());
            cancellations.add(cancellationRepository.save(cancellation));

            affectedSenderIds.add(bid.getSenderId());
            affectedBidIds.add(bid.getId());
        }

        // Track cancellation count on traveler profile for reputation penalty
        traveler.setCancellationCount(traveler.getCancellationCount() + 1);
        userRepository.save(traveler);

        int cancellationCount = traveler.getCancellationCount();

        auditService.log("ANNOUNCEMENT", request.announcementId(), "TRIP_CANCELLED", traveler.getId(),
                Map.of("reason", request.reason(),
                       "affectedBids", String.valueOf(affectedBids.size()),
                       "cancellationCount", String.valueOf(cancellationCount)));

        if (cancellationCount >= 3) {
            auditService.log("USER", traveler.getId(), "HIGH_CANCELLATION_ALERT", traveler.getId(),
                    Map.of("cancellationCount", String.valueOf(cancellationCount),
                           "triggeringAnnouncementId", request.announcementId().toString()));
            eventPublisher.publishEvent(new TravelerHighCancellationEvent(
                    traveler.getId(), cancellationCount, request.announcementId()));
        }

        // Build bidPaymentMethods so listeners (e.g. WalletCancellationListener) don't need BidRepository
        Map<UUID, String> bidPaymentMethods = new HashMap<>();
        Map<UUID, String> bidCommissionChargedVia = new HashMap<>();
        for (BidEntity bid : affectedBids) {
            String methodName = bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : "STRIPE";
            bidPaymentMethods.put(bid.getId(), methodName);
            if (bid.getCommissionChargedVia() != null) {
                bidCommissionChargedVia.put(bid.getId(), bid.getCommissionChargedVia().name());
            }
        }

        // Publish event for notifications (Epic 8) and payment refunds (Story 6.7)
        eventPublisher.publishEvent(new TripCancelledEvent(
                request.announcementId(), traveler.getId(), affectedSenderIds, request.reason(),
                affectedBidIds, bidPaymentMethods, bidCommissionChargedVia));

        // Generate rematch suggestions for each affected bid
        List<RematchSuggestionDto> suggestions = generateRematchSuggestions(
                announcement, affectedBids, cancellations);

        return new CancellationResponse(
                request.announcementId(),
                affectedBids.size(),
                request.reason(),
                suggestions,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    @Transactional(readOnly = true)
    public List<RematchSuggestionDto> getRematchSuggestions(UUID cancellationId, String firebaseUid) {
        CancellationEntity cancellation = cancellationRepository.findById(cancellationId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "cancellation-not-found", "Not Found", "Annulation introuvable"));

        UserEntity caller = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));

        BidEntity bid = bidRepository.findById(cancellation.getBidId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Not Found", "Annonce introuvable"));

        boolean isParticipant = caller.getId().equals(bid.getSenderId())
                || caller.getId().equals(announcement.getTravelerId())
                || caller.getId().equals(cancellation.getCancelledBy());
        if (!isParticipant) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN,
                    "not-participant", "Forbidden",
                    "Vous n'êtes pas concerné par cette annulation");
        }

        return rematchSuggestionRepository.findByCancellationId(cancellationId)
                .stream().map(s -> {
                    AnnouncementEntity a = announcementRepository.findById(s.getAnnouncementId()).orElse(null);
                    if (a == null) return null;
                    return new RematchSuggestionDto(s.getId(), a.getId(),
                            a.getDepartureCity(), a.getArrivalCity(),
                            a.getDepartureDate(), a.getAvailableKg(), a.getPricePerKg());
                })
                .filter(s -> s != null)
                .toList();
    }

    private List<RematchSuggestionDto> generateRematchSuggestions(
            AnnouncementEntity cancelled,
            List<BidEntity> affectedBids,
            List<CancellationEntity> cancellations) {

        if (affectedBids.isEmpty()) return List.of();

        // Find alternatives on same corridor within 72h
        LocalDate from = cancelled.getDepartureDate();
        LocalDate to = from.plusDays(3);

        // Find active announcements on same corridor, within 72h, with capacity
        List<AnnouncementEntity> alternatives = announcementRepository.findAll().stream()
                .filter(a -> a.getStatus() == AnnouncementStatus.ACTIVE)
                .filter(a -> !a.getId().equals(cancelled.getId()))
                .filter(a -> a.getDepartureCity().equalsIgnoreCase(cancelled.getDepartureCity()))
                .filter(a -> a.getArrivalCity().equalsIgnoreCase(cancelled.getArrivalCity()))
                .filter(a -> !a.getDepartureDate().isBefore(from) && !a.getDepartureDate().isAfter(to))
                .limit(5)
                .toList();

        List<RematchSuggestionDto> result = new ArrayList<>();

        // Create rematch suggestion records for the first affected bid's cancellation
        if (!cancellations.isEmpty() && !alternatives.isEmpty()) {
            CancellationEntity firstCancellation = cancellations.get(0);
            for (AnnouncementEntity alt : alternatives) {
                RematchSuggestionEntity suggestion = new RematchSuggestionEntity();
                suggestion.setCancellationId(firstCancellation.getId());
                suggestion.setAnnouncementId(alt.getId());
                RematchSuggestionEntity saved = rematchSuggestionRepository.save(suggestion);

                result.add(new RematchSuggestionDto(saved.getId(), alt.getId(),
                        alt.getDepartureCity(), alt.getArrivalCity(),
                        alt.getDepartureDate(), alt.getAvailableKg(), alt.getPricePerKg()));
            }
        }

        return result;
    }

    @Transactional
    public CancellationEntity reportSenderNoShow(UUID bidId, UUID travelerId) {
        BidEntity bid = bidRepository.findById(bidId).orElseThrow();
        if (bid.getStatus() != BidStatus.ACCEPTED) {
            throw new IllegalStateException("Le bid doit être en statut ACCEPTED.");
        }
        LocalDateTime handoverEnd = bid.getHandoverWindowEnd();
        if (handoverEnd == null || LocalDateTime.now().isBefore(handoverEnd)) {
            throw new IllegalStateException("Vous ne pouvez signaler qu'après l'heure de remise prévue.");
        }
        if (cancellationRepository.existsByBidIdAndNoShowStatusIn(bidId,
                List.of(CancellationStatus.PENDING_CONFIRMATION, CancellationStatus.CONFIRMED))) {
            throw new IllegalStateException("Une annulation est déjà en cours pour ce bid.");
        }
        CancellationEntity c = new CancellationEntity();
        c.setBidId(bidId);
        c.setCancelledBy(travelerId);
        c.setReason(CancellationReason.SENDER_NO_SHOW.name());
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        c.setContestationDeadline(
                OffsetDateTime.now().plusHours(commissionProperties.noShowContestationHours()));
        return cancellationRepository.save(c);
    }

    /**
     * L'expéditeur signale un voyageur absent (no-show manuel). Vérifie l'ownership, le statut
     * ACCEPTED et que la fenêtre de remise est passée, puis publie
     * {@link TravelerNoShowReportedEvent} — écouté côté matching/ par {@code NoShowService}.
     */
    @Transactional
    public void reportTravelerNoShow(UUID bidId, UUID senderId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        if (!bid.getSenderId().equals(senderId)) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas l'expéditeur de ce bid.");
        }
        if (bid.getStatus() != BidStatus.ACCEPTED) {
            throw new IllegalStateException("Le bid doit être en statut ACCEPTED.");
        }
        LocalDateTime handoverEnd = bid.getHandoverWindowEnd();
        if (handoverEnd == null || LocalDateTime.now().isBefore(handoverEnd)) {
            throw new IllegalStateException("Vous ne pouvez signaler qu'après l'heure de remise prévue.");
        }
        auditService.log("BID", bidId, "TRAVELER_NO_SHOW_REPORTED", senderId,
                Map.of("bidId", bidId.toString()));
        eventPublisher.publishEvent(new TravelerNoShowReportedEvent(bidId, senderId));
    }

    /**
     * Annulation après remise du colis (HANDED_OVER) par l'expéditeur OU le voyageur.
     * Verrou D3, restauration du kilo, remboursement intégral (via per-bid
     * {@link TripCancelledEvent}), génération du code de retour (D7). MVP : aucune
     * pénalité monétaire.
     */
    @Transactional
    public void cancelAfterHandover(String firebaseUid, UUID bidId) {
        UserEntity caller = findUserByFirebaseUid(firebaseUid);
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElse(null);

        boolean isSender = bid.getSenderId().equals(caller.getId());
        boolean isTraveler = announcement != null
                && announcement.getTravelerId() != null
                && announcement.getTravelerId().equals(caller.getId());
        if (!isSender && !isTraveler) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas partie prenante de ce bid.");
        }

        if (bid.getStatus() != BidStatus.HANDED_OVER) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "invalid-status", "Invalid Status",
                    "L'annulation après remise n'est possible que sur un colis remis (HANDED_OVER).");
        }

        CancellationGuard.assertCancellable(bid, announcement);

        if (cancellationRepository.findByBidId(bidId).isPresent()) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "already-cancelled",
                    "Already Cancelled", "Une annulation existe déjà pour ce bid.");
        }

        CancellationActor actor = isSender ? CancellationActor.SENDER : CancellationActor.TRAVELER;
        CancellationReason reason = isSender
                ? CancellationReason.SENDER_CANCEL_AFTER_HANDOVER
                : CancellationReason.TRAVELER_CANCEL_AFTER_HANDOVER;

        // Restaurer le kilo au voyageur (sauf KG_FREE) + rouvrir l'annonce si FULL.
        if (announcement != null) {
            boolean isKgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
            if (!isKgFree && bid.getWeightKg() != null) {
                announcement.setAvailableKg(announcement.getAvailableKg().add(bid.getWeightKg()));
            }
            if (!isKgFree && announcement.getStatus() == AnnouncementStatus.FULL) {
                announcement.setStatus(AnnouncementStatus.ACTIVE);
            }
            announcementRepository.save(announcement);
        }

        // Code de retour : détenu par l'expéditeur, saisi par le voyageur (tranche C).
        LocalDateTime now = LocalDateTime.now();
        bid.setReturnCode(String.format("%06d", RETURN_CODE_RANDOM.nextInt(1_000_000)));
        bid.setReturnCodeExpiry(now.plusDays(3));
        bid.setReturnCodeAttempts(0);
        bid.setReturnDeadline(now.plusDays(3));
        bid.setStatus(BidStatus.CANCELLED);
        bidRepository.save(bid);

        CancellationEntity c = new CancellationEntity();
        c.setBidId(bidId);
        c.setCancelledBy(caller.getId());
        c.setReason(reason.name());
        c.setNoShowStatus(CancellationStatus.CONFIRMED);
        cancellationRepository.save(c);

        // Réputation (D8 : l'annulation après remise est immédiatement CONFIRMED).
        // Voyageur → compteur d'annulations existant ; expéditeur → compteur d'incidents
        // de remise dédié (distinct du no-show voyageur).
        if (actor == CancellationActor.TRAVELER) {
            caller.setCancellationCount(caller.getCancellationCount() + 1);
        } else {
            caller.setSenderHandoverIncidentCount(caller.getSenderHandoverIncidentCount() + 1);
        }
        userRepository.save(caller);

        auditService.log("BID", bidId, "BID_CANCELLED_AFTER_HANDOVER", caller.getId(),
                Map.of("actor", actor.name(),
                       "paymentMethod",
                       bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : "STRIPE"));
        auditService.log("BID", bidId, "RETURN_CODE_GENERATED", caller.getId(),
                Map.of("returnDeadline", String.valueOf(bid.getReturnDeadline())));

        // Remboursement intégral réutilisant la matrice de TripCancelledEvent (per-bid).
        Map<UUID, String> bidPaymentMethods = new HashMap<>();
        Map<UUID, String> bidCommissionChargedVia = new HashMap<>();
        bidPaymentMethods.put(bidId,
                bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : "STRIPE");
        if (bid.getCommissionChargedVia() != null) {
            bidCommissionChargedVia.put(bidId, bid.getCommissionChargedVia().name());
        }
        eventPublisher.publishEvent(new TripCancelledEvent(
                bid.getAnnouncementId(),
                announcement != null ? announcement.getTravelerId() : null,
                List.of(bid.getSenderId()), reason.name(),
                List.of(bidId), bidPaymentMethods, bidCommissionChargedVia));
    }

    /**
     * Le voyageur saisit le code de retour (détenu par l'expéditeur) pour confirmer
     * la restitution physique du colis (D7). Réutilise la plomberie du confirmationCode
     * en sens inverse (expiry / tentatives / égalité). Publie {@link ParcelReturnedEvent}.
     */
    @Transactional
    public ReturnCodeResponse confirmReturn(String firebaseUid, UUID bidId, String code) {
        UserEntity caller = findUserByFirebaseUid(firebaseUid);
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Not Found", "Annonce introuvable"));

        if (announcement.getTravelerId() == null
                || !announcement.getTravelerId().equals(caller.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul le voyageur peut confirmer le retour du colis.");
        }
        if (bid.getReturnCode() == null) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-not-generated",
                    "Code Not Generated", "Aucun retour de colis en attente pour ce bid.");
        }
        if (bid.getReturnedAt() != null) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "already-returned",
                    "Already Returned", "Le colis a déjà été marqué comme rendu.");
        }
        if (bid.getReturnCodeExpiry() != null
                && LocalDateTime.now().isAfter(bid.getReturnCodeExpiry())) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-expired",
                    "Code Expired", "Le code de retour a expiré — contactez le support.");
        }
        if (bid.getReturnCodeAttempts() >= MAX_RETURN_CODE_ATTEMPTS) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "too-many-attempts",
                    "Too Many Attempts", "Trop de tentatives — contactez le support.");
        }
        if (!bid.getReturnCode().equals(code)) {
            bid.setReturnCodeAttempts(bid.getReturnCodeAttempts() + 1);
            bidRepository.save(bid);
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "code-incorrect",
                    "Code Incorrect", "Code de retour incorrect.");
        }

        bid.setReturnedAt(LocalDateTime.now());
        bid.setReturnCode(null);
        bid.setReturnCodeExpiry(null);
        bid.setReturnCodeAttempts(0);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "PARCEL_RETURNED", caller.getId(),
                Map.of("bidId", bidId.toString()));
        eventPublisher.publishEvent(
                new ParcelReturnedEvent(bidId, announcement.getTravelerId(), bid.getSenderId()));

        return new ReturnCodeResponse(null, bid.getReturnDeadline(), bid.getReturnedAt());
    }

    /** L'expéditeur consulte son code de retour (à communiquer au voyageur) + l'état du retour. */
    @Transactional(readOnly = true)
    public ReturnCodeResponse getReturnCode(String firebaseUid, UUID bidId) {
        UserEntity caller = findUserByFirebaseUid(firebaseUid);
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        if (!bid.getSenderId().equals(caller.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul l'expéditeur peut consulter le code de retour.");
        }
        return new ReturnCodeResponse(bid.getReturnCode(), bid.getReturnDeadline(), bid.getReturnedAt());
    }

    @Transactional
    public void confirmSenderNoShow(UUID bidId) {
        CancellationEntity c = cancellationRepository.findByBidId(bidId).orElseThrow();
        if (c.getNoShowStatus() != CancellationStatus.PENDING_CONFIRMATION) return;

        c.setNoShowStatus(CancellationStatus.CONFIRMED);
        cancellationRepository.save(c);
        eventPublisher.publishEvent(
                new CancellationConfirmedEvent(bidId, c.getId(), CancellationReason.SENDER_NO_SHOW));
    }

    @Transactional
    public void contestSenderNoShow(UUID bidId, UUID senderId) {
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        if (!bid.getSenderId().equals(senderId)) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas l'expéditeur de ce bid.");
        }

        CancellationEntity c = cancellationRepository.findByBidId(bidId).orElseThrow();
        if (c.getContestationDeadline() == null
                || OffsetDateTime.now().isAfter(c.getContestationDeadline())) {
            throw new IllegalStateException("Le délai de contestation est dépassé.");
        }
        c.setNoShowStatus(CancellationStatus.CONTESTED);
        cancellationRepository.save(c);

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElseThrow();
        eventPublisher.publishEvent(new DisputeOpenedEvent(
                bidId, bid.getSenderId(), announcement.getTravelerId()));
        auditService.log("CANCELLATION", c.getId(), "SENDER_NO_SHOW_CONTESTED", senderId,
                Map.of("bidId", bidId.toString()));
    }

    private UserEntity findUserByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
    }
}
