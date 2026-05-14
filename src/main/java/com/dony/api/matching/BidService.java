package com.dony.api.matching;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.BidRejectRequest;
import com.dony.api.matching.dto.BidRequest;
import com.dony.api.matching.dto.BidResponse;
import com.dony.api.matching.dto.HandoverRequest;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.matching.events.HandoverDefinedEvent;
import com.dony.api.ratings.RatingRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class BidService {

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final RatingRepository ratingRepository;

    @Value("${dony.kyc.enforce:true}")
    private boolean enforceKyc;

    public BidService(BidRepository bidRepository, AnnouncementRepository announcementRepository,
                      UserRepository userRepository, AuditService auditService,
                      ApplicationEventPublisher eventPublisher, RatingRepository ratingRepository) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.ratingRepository = ratingRepository;
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidResponse createBid(UUID announcementId, String firebaseUid,
                                 BidRequest request, HttpServletRequest httpRequest) {

        UserEntity sender = findUserByFirebaseUid(firebaseUid);

        if (enforceKyc && sender.getKycStatus() != KycStatus.VERIFIED) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "kyc-not-verified", "KYC Not Verified",
                    "Vous devez compléter votre vérification d'identité pour effectuer cette action");
        }

        if (!sender.getRoles().contains(Role.SENDER)) {
            sender.getRoles().add(Role.SENDER);
            userRepository.save(sender);
        }

        AnnouncementEntity announcement = findAnnouncement(announcementId);

        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT, "announcement-not-active", "Announcement Not Active",
                    "Cette annonce n'est plus disponible");
        }

        if (announcement.getTravelerId().equals(sender.getId())) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT, "cannot-bid-own-announcement", "Cannot Bid Own Announcement",
                    "Vous ne pouvez pas faire une demande sur votre propre annonce");
        }

        boolean alreadyHasBid = bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
                sender.getId(), announcementId,
                List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED));
        if (alreadyHasBid) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT, "already-bid", "Demande existante",
                    "Vous avez déjà une demande en cours ou acceptée pour ce trajet");
        }

        if (request.weightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "weight-exceeds-capacity", "Weight Exceeds Capacity",
                    "Poids demandé supérieur à la capacité disponible");
        }

        if (request.declaredValueEur().compareTo(BigDecimal.valueOf(500)) > 0) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "value-exceeds-limit", "Value Exceeds Limit",
                    "Valeur maximum : 500 €");
        }

        if (Boolean.FALSE.equals(request.disclaimerSigned())) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "disclaimer-not-signed", "Disclaimer Not Signed",
                    "Le disclaimer légal doit être accepté");
        }

        String clientIp = resolveClientIp(httpRequest);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(sender.getId());
        bid.setWeightKg(request.weightKg());
        bid.setDeclaredValueEur(request.declaredValueEur());
        bid.setDescription(request.description());
        bid.setContentCategory(request.contentCategory());
        bid.setRecipientName(request.recipientName());
        bid.setRecipientPhone(request.recipientPhone());
        bid.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
        bid.setDisclaimerSignedIp(clientIp);
        bid.setStatus(BidStatus.PENDING);

        BidEntity saved = bidRepository.save(bid);

        auditService.log("BID", saved.getId(), "BID_CREATED", sender.getId(),
                Map.of(
                        "announcementId", announcementId.toString(),
                        "weightKg", saved.getWeightKg().toString(),
                        "declaredValueEur", saved.getDeclaredValueEur().toString(),
                        "contentCategory", String.valueOf(saved.getContentCategory()),
                        "disclaimerSignedAt", saved.getDisclaimerSignedAt().toString(),
                        "disclaimerSignedIp", clientIp
                ));

        // Note: BidCreatedEvent is no longer published here. Traveler notification
        // happens after the sender's payment is authorized — see
        // PaymentService.promoteBidOnPaymentAuthorized().

        return toResponse(saved, sender);
    }

    @Transactional(readOnly = true)
    public void assertSenderOwnsBid(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        UserEntity user = findUserByFirebaseUid(firebaseUid);
        if (!bid.getSenderId().equals(user.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Seul l'expéditeur peut confirmer le paiement");
        }
    }

    @Transactional(readOnly = true)
    public BidResponse getBidById(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        UserEntity requester = findUserByFirebaseUid(firebaseUid);

        // Accessible by the traveler who owns the announcement, or the sender
        boolean isTraveler = announcement.getTravelerId().equals(requester.getId());
        boolean isSender = bid.getSenderId().equals(requester.getId());

        if (!isTraveler && !isSender) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Accès non autorisé à ce bid");
        }

        UserEntity sender = userRepository.findById(bid.getSenderId()).orElse(null);
        return toResponse(bid, sender, requester.getId());
    }

    @Transactional(readOnly = true)
    public List<BidResponse> getBidsForAnnouncement(UUID announcementId, String firebaseUid) {
        AnnouncementEntity announcement = findAnnouncement(announcementId);
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à voir ces demandes");
        }

        return bidRepository.findByAnnouncementId(announcementId)
                .stream()
                .filter(b -> !b.isDeletedByTraveler())
                .filter(b -> b.getStatus() != BidStatus.AWAITING_PAYMENT)
                .map(b -> {
                    UserEntity sender = userRepository.findById(b.getSenderId()).orElse(null);
                    return toResponse(b, sender);
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<BidResponse> getMyBids(String firebaseUid) {
        UserEntity user = findUserByFirebaseUid(firebaseUid);
        return bidRepository.findBySenderId(user.getId())
                .stream()
                .filter(b -> !b.isDeletedBySender())
                .map(b -> toResponse(b, user))
                .toList();
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidResponse acceptBid(UUID bidId, String firebaseUid) {
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Demande introuvable"));
        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(bid.getAnnouncementId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        requireTravelerOwnsAnnouncement(traveler, announcement);
        requireBidStatus(bid, BidStatus.PAYMENT_ESCROWED);

        if (announcement.getStatus() == AnnouncementStatus.IN_PROGRESS
                || announcement.getStatus() == AnnouncementStatus.COMPLETED
                || announcement.getStatus() == AnnouncementStatus.CANCELLED) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                    "announcement-not-accepting", "Announcement Not Accepting",
                    "Le voyageur est déjà parti, ce trajet n'accepte plus de colis");
        }

        if (bid.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT, "capacity-insufficient", "Insufficient Capacity",
                    "Capacité insuffisante pour accepter cette demande");
        }

        bid.setStatus(BidStatus.ACCEPTED);
        if (bid.getQrToken() == null) {
            bid.setQrToken(UUID.randomUUID().toString());
        }
        if (bid.getTrackingNumber() == null) {
            bid.setTrackingNumber(generateTrackingNumber());
        }
        if (bid.getTrackingToken() == null) {
            bid.setTrackingToken(java.util.UUID.randomUUID().toString());
        }
        announcement.setAvailableKg(announcement.getAvailableKg().subtract(bid.getWeightKg()));
        if (announcement.getAvailableKg().compareTo(BigDecimal.ZERO) <= 0) {
            announcement.setStatus(AnnouncementStatus.FULL);
        }
        announcementRepository.save(announcement);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "BID_ACCEPTED", traveler.getId(),
                Map.of("announcementId", announcement.getId().toString(),
                       "weightKg", bid.getWeightKg().toString()));

        eventPublisher.publishEvent(new BidAcceptedEvent(
                bidId, bid.getSenderId(), traveler.getId(), announcement.getId()));

        return toResponse(bid, userRepository.findById(bid.getSenderId()).orElse(null));
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidResponse rejectBid(UUID bidId, String firebaseUid, BidRejectRequest request) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        requireTravelerOwnsAnnouncement(traveler, announcement);
        requireBidStatus(bid, BidStatus.PAYMENT_ESCROWED);

        bid.setStatus(BidStatus.REJECTED);
        if (request != null) {
            bid.setRejectionReason(request.reason());
        }
        bidRepository.save(bid);

        auditService.log("BID", bidId, "BID_REJECTED", traveler.getId(),
                Map.of("reason", String.valueOf(bid.getRejectionReason())));

        eventPublisher.publishEvent(new BidRejectedEvent(
                bidId, bid.getSenderId(), bid.getRejectionReason()));

        return toResponse(bid, userRepository.findById(bid.getSenderId()).orElse(null));
    }

    @Transactional
    public BidResponse setHandover(UUID bidId, String firebaseUid, HandoverRequest request) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        requireTravelerOwnsAnnouncement(traveler, announcement);

        if (bid.getStatus() != BidStatus.ACCEPTED) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "bid-not-accepted", "Bid Not Accepted",
                    "La fenêtre de remise ne peut être définie que sur un bid accepté");
        }

        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-window",
                    "Invalid Window", "La fin de la fenêtre doit être après le début");
        }

        bid.setHandoverLocation(request.location());
        bid.setHandoverWindowStart(request.windowStart());
        bid.setHandoverWindowEnd(request.windowEnd());
        bidRepository.save(bid);

        auditService.log("BID", bidId, "HANDOVER_DEFINED", traveler.getId(),
                Map.of("location", request.location(),
                       "windowStart", request.windowStart().toString(),
                       "windowEnd", request.windowEnd().toString()));

        eventPublisher.publishEvent(new HandoverDefinedEvent(
                bidId, bid.getSenderId(), request.location(),
                request.windowStart(), request.windowEnd()));

        return toResponse(bid, userRepository.findById(bid.getSenderId()).orElse(null));
    }

    @Transactional
    public BidResponse confirmPresence(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        requireTravelerOwnsAnnouncement(traveler, announcement);

        if (bid.getStatus() != BidStatus.ACCEPTED) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "bid-not-accepted", "Bid Not Accepted",
                    "Confirmation de présence uniquement pour les bids acceptés");
        }

        bid.setVoyageurConfirmed(true);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "PRESENCE_CONFIRMED", traveler.getId(), Map.of());

        return toResponse(bid, userRepository.findById(bid.getSenderId()).orElse(null));
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidResponse cancelBid(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        UserEntity sender = findUserByFirebaseUid(firebaseUid);

        if (!bid.getSenderId().equals(sender.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à annuler ce bid");
        }

        if (bid.getStatus() == BidStatus.CANCELLED || bid.getStatus() == BidStatus.REJECTED
                || bid.getStatus() == BidStatus.COMPLETED) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "invalid-status", "Invalid Status",
                    "Impossible d'annuler un bid déjà terminé");
        }

        // Si le bid était déjà accepté ou remis, on rend le kilo au voyageur
        if (bid.getStatus() == BidStatus.ACCEPTED || bid.getStatus() == BidStatus.HANDED_OVER) {
            AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
            if (announcement != null) {
                announcement.setAvailableKg(announcement.getAvailableKg().add(bid.getWeightKg()));
                if (announcement.getStatus() == AnnouncementStatus.FULL) {
                    announcement.setStatus(AnnouncementStatus.ACTIVE);
                }
                announcementRepository.save(announcement);
            }
        }

        bid.setStatus(BidStatus.CANCELLED);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "BID_CANCELLED", sender.getId(), Map.of());

        eventPublisher.publishEvent(new BidRejectedEvent(
                bid.getId(), bid.getSenderId(), "CANCELLED_BY_SENDER"));

        return toResponse(bid, sender);
    }

    @Transactional
    public void hideBidForSender(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        UserEntity sender = findUserByFirebaseUid(firebaseUid);

        if (!bid.getSenderId().equals(sender.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à masquer ce bid");
        }

        bid.setDeletedBySender(true);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "BID_HIDDEN_BY_SENDER", sender.getId(), Map.of());
    }

    @Transactional
    public void hideBidForTraveler(UUID bidId, String firebaseUid) {
        BidEntity bid = findBid(bidId);
        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);

        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à effectuer cette action");
        }

        if (bid.getStatus() != BidStatus.REJECTED && bid.getStatus() != BidStatus.CANCELLED) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "invalid-bid-status", "Invalid Bid Status",
                    "Seules les demandes refusées ou annulées peuvent être supprimées");
        }

        bid.setDeletedByTraveler(true);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "BID_DISMISSED_BY_TRAVELER", traveler.getId(), Map.of());
    }

    // Called by scheduler — no auth check, transaction managed internally
    // Story 9.4 — Refus de colis par le voyageur lors de l'inspection
    @Transactional
    public BidResponse refuseParcel(UUID bidId, String firebaseUid,
                                    String reason, String refusalPhotoUrl) {
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);
        BidEntity bid = findBid(bidId);

        AnnouncementEntity announcement = findAnnouncement(bid.getAnnouncementId());
        requireTravelerOwnsAnnouncement(traveler, announcement);

        if (bid.getStatus() != BidStatus.ACCEPTED && bid.getStatus() != BidStatus.HANDED_OVER) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-bid-status",
                    "Unprocessable", "Le refus de colis n'est possible que sur un envoi accepté ou remis");
        }

        bid.setStatus(BidStatus.PARCEL_REFUSED);
        bid.setRefusalReason(reason);
        bid.setRefusalPhotoUrl(refusalPhotoUrl);
        bidRepository.save(bid);

        UserEntity sender = userRepository.findById(bid.getSenderId()).orElse(null);
        if (sender != null) {
            sender.setRefusedCount(sender.getRefusedCount() + 1);
            userRepository.save(sender);
        }

        eventPublisher.publishEvent(new com.dony.api.matching.events.ParcelRefusedEvent(
                bid.getId(), traveler.getId(), bid.getSenderId(), reason));

        auditService.log("BID", bid.getId(), "PARCEL_REFUSED", traveler.getId(),
                Map.of("reason", reason != null ? reason : "",
                        "senderId", bid.getSenderId().toString()));

        return toResponse(bid, sender);
    }

    @Transactional
    public void markH2AlertSent(UUID bidId) {
        bidRepository.findById(bidId).ifPresent(bid -> {
            bid.setH2AlertSentAt(LocalDateTime.now(ZoneOffset.UTC));
            bidRepository.save(bid);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildSenderName(UserEntity user) {
        if (user == null) return null;
        String first = user.getFirstName();
        String last = user.getLastName();
        if (first != null && !first.isBlank() && last != null && !last.isBlank())
            return first.trim() + " " + last.trim();
        if (first != null && !first.isBlank()) return first.trim();
        if (last != null && !last.isBlank()) return last.trim();
        return null;
    }

    private UserEntity findUserByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private AnnouncementEntity findAnnouncement(UUID id) {
        return announcementRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
    }

    private BidEntity findBid(UUID id) {
        return bidRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Bid Not Found", "Demande introuvable"));
    }

    private void requireTravelerOwnsAnnouncement(UserEntity traveler, AnnouncementEntity announcement) {
        if (!announcement.getTravelerId().equals(traveler.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à effectuer cette action");
        }
    }

    private void requireBidStatus(BidEntity bid, BidStatus expected) {
        if (bid.getStatus() != expected) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "invalid-bid-status", "Invalid Bid Status",
                    "Cette action n'est pas possible pour un bid en statut " + bid.getStatus());
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            // Use the last value added by the trusted proxy — the client cannot spoof it
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private static final String TRACKING_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final java.security.SecureRandom SECURE_RNG = new java.security.SecureRandom();

    static String generateTrackingNumber() {
        StringBuilder sb = new StringBuilder("DON-");
        for (int i = 0; i < 8; i++) {
            sb.append(TRACKING_CHARS.charAt(SECURE_RNG.nextInt(TRACKING_CHARS.length())));
        }
        return sb.toString();
    }

    BidResponse toResponse(BidEntity bid, UserEntity sender) {
        return toResponse(bid, sender, null);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return null;
        return phone.substring(0, Math.min(4, phone.length())) + "••••••" + phone.substring(phone.length() - 2);
    }

    BidResponse toResponse(BidEntity bid, UserEntity sender, UUID callerId) {
        String senderName = buildSenderName(sender);
        String senderPhone = sender != null ? maskPhone(sender.getPhoneNumber()) : null;
        Integer senderTotalShipments = sender != null ? sender.getTotalShipments() : null;
        boolean senderKycVerified = sender != null
                && sender.getKycStatus() == com.dony.api.auth.KycStatus.VERIFIED;
        boolean senderIsProAccount = sender != null && sender.isProAccount();
        boolean senderKiloPro = sender != null && sender.isKiloPro();
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        String departureCity = announcement != null ? announcement.getDepartureCity() : "Inconnu";
        String arrivalCity = announcement != null ? announcement.getArrivalCity() : "Inconnu";
        java.time.LocalDate departureDate = announcement != null ? announcement.getDepartureDate() : null;
        java.time.LocalTime departureTime = announcement != null ? announcement.getDepartureTime() : null;
        java.time.LocalTime arrivalTime = announcement != null ? announcement.getArrivalTime() : null;
        java.math.BigDecimal pricePerKg = announcement != null ? announcement.getPricePerKg() : null;
        com.dony.api.matching.TransportMode transportMode = announcement != null ? announcement.getTransportMode() : null;
        String confirmationCode = (callerId != null && callerId.equals(bid.getSenderId()))
                ? bid.getConfirmationCode() : null;

        UserEntity traveler = (announcement != null)
                ? userRepository.findById(announcement.getTravelerId()).orElse(null)
                : null;
        UUID travelerId = traveler != null ? traveler.getId() : null;
        String travelerName = buildSenderName(traveler);
        String travelerPhone = traveler != null ? maskPhone(traveler.getPhoneNumber()) : null;
        boolean travelerKycVerified = traveler != null
                && traveler.getKycStatus() == com.dony.api.auth.KycStatus.VERIFIED;
        boolean travelerIsProAccount = traveler != null && traveler.isProAccount();
        boolean travelerKiloPro = traveler != null && traveler.isKiloPro();
        Integer travelerTotalTrips = traveler != null ? traveler.getTotalTrips() : null;
        java.math.BigDecimal travelerAverageRating = traveler != null ? traveler.getAverageRating() : null;

        boolean senderHasRated = ratingRepository.existsByBidIdAndRaterId(bid.getId(), bid.getSenderId());
        boolean travelerHasRated = travelerId != null
                && ratingRepository.existsByBidIdAndRaterId(bid.getId(), travelerId);

        return new BidResponse(
                bid.getId(),
                bid.getAnnouncementId(),
                bid.getSenderId(),
                senderName,
                senderPhone,
                senderTotalShipments,
                senderKycVerified,
                senderIsProAccount,
                senderKiloPro,
                bid.getWeightKg(),
                bid.getDeclaredValueEur(),
                bid.getDescription(),
                bid.getContentCategory(),
                bid.getRecipientName(),
                bid.getRecipientPhone(),
                bid.getStatus().name(),
                bid.getRejectionReason(),
                bid.getHandoverLocation(),
                bid.getHandoverWindowStart(),
                bid.getHandoverWindowEnd(),
                bid.isVoyageurConfirmed(),
                bid.getDisclaimerSignedAt(),
                bid.getCreatedAt(),
                bid.getUpdatedAt(),
                departureCity,
                arrivalCity,
                departureDate,
                departureTime,
                arrivalTime,
                pricePerKg,
                transportMode,
                bid.getTrackingNumber(),
                bid.getTrackingToken(),
                confirmationCode,
                travelerId,
                travelerName,
                travelerPhone,
                travelerKycVerified,
                travelerIsProAccount,
                travelerKiloPro,
                travelerTotalTrips,
                travelerAverageRating,
                senderHasRated,
                travelerHasRated,
                bid.getConfirmationCodeRefreshCount(),
                bid.getConfirmationCodeRefreshWindowStart()
        );
    }
}
