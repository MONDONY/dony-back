package com.dony.api.matching;

import com.dony.api.auth.BlockService;
import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.CommissionRateResolver;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.StorageService;
import com.dony.api.matching.dto.BidGridItemRequest;
import com.dony.api.matching.dto.BidQuoteRequest;
import com.dony.api.matching.dto.BidQuoteResponse;
import com.dony.api.matching.dto.BidRejectRequest;
import com.dony.api.matching.dto.BidRequest;
import com.dony.api.matching.dto.BidResponse;
import com.dony.api.promo.PromoService;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.cancellation.CancellationRepository;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.ratings.RatingRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
    private final CancellationRepository cancellationRepository;
    private final BidGridItemRepository bidGridItemRepository;
    private final AnnouncementPriceGridItemRepository annGridItemRepository;
    private final BlockService blockService;
    private final CommissionRateResolver commissionRateResolver;
    private final PromoService promoService;
    private final StorageService storageService;
    private final BidPhotoService bidPhotoService;

    @Value("${dony.kyc.enforce:true}")
    private boolean enforceKyc;

    public BidService(BidRepository bidRepository, AnnouncementRepository announcementRepository,
                      UserRepository userRepository, AuditService auditService,
                      ApplicationEventPublisher eventPublisher, RatingRepository ratingRepository,
                      CancellationRepository cancellationRepository,
                      BidGridItemRepository bidGridItemRepository,
                      AnnouncementPriceGridItemRepository annGridItemRepository,
                      BlockService blockService,
                      CommissionRateResolver commissionRateResolver,
                      PromoService promoService,
                      StorageService storageService,
                      BidPhotoService bidPhotoService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.ratingRepository = ratingRepository;
        this.cancellationRepository = cancellationRepository;
        this.bidGridItemRepository = bidGridItemRepository;
        this.annGridItemRepository = annGridItemRepository;
        this.blockService = blockService;
        this.commissionRateResolver = commissionRateResolver;
        this.promoService = promoService;
        this.storageService = storageService;
        this.bidPhotoService = bidPhotoService;
    }

    /**
     * Devis : calcule le total exact (net, commission, total) avec promo éventuel.
     * Le promo est validé strictement ici (exceptions propagées au contrôleur).
     */
    @Transactional(readOnly = true)
    public BidQuoteResponse quote(String firebaseUid, BidQuoteRequest request) {
        UserEntity sender = findUserByFirebaseUid(firebaseUid);
        AnnouncementEntity ann = announcementRepository.findById(request.announcementId())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        // Même détermination de mode que createBid/checkout : au moins poids OU article.
        List<BidGridItemRequest> gridItems = request.gridItems() != null ? request.gridItems() : List.of();
        boolean hasGrid = !gridItems.isEmpty();
        boolean hasKg   = request.weightKg() != null && request.weightKg().compareTo(BigDecimal.ZERO) > 0;

        if (!hasGrid && !hasKg) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "bid-empty", "Bid Empty",
                    "Au moins un article ou un poids doit être renseigné");
        }

        // Net grille : Σ (prix unitaire net × quantité), articles bornés à CETTE annonce.
        BigDecimal gridNet = BigDecimal.ZERO;
        for (BidGridItemRequest g : gridItems) {
            AnnouncementPriceGridItemEntity annItem = annGridItemRepository.findById(g.announcementGridItemId())
                    .filter(i -> i.getAnnouncementId().equals(ann.getId()))
                    .orElseThrow(() -> new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "invalid-grid-item", "Invalid Grid Item",
                            "Article hors grille de cette annonce : " + g.announcementGridItemId()));
            gridNet = gridNet.add(annItem.getUnitPriceNet().multiply(BigDecimal.valueOf(g.quantity())));
        }
        gridNet = gridNet.setScale(2, java.math.RoundingMode.HALF_UP);

        // Net poids : prix au kilo × poids (mode KG/MIXED).
        BigDecimal kgNet = BigDecimal.ZERO;
        if (hasKg) {
            if (ann.getPricePerKg() == null) {
                throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-bid-params", "Invalid Bid Parameters",
                        "Le prix au kilo est requis pour calculer le devis au poids");
            }
            kgNet = ann.getPricePerKg().multiply(request.weightKg()).setScale(2, java.math.RoundingMode.HALF_UP);
        }

        BigDecimal netEur = gridNet.add(kgNet).setScale(2, java.math.RoundingMode.HALF_UP);

        // Résolution du taux — promo validé strictement (exceptions propagées en RFC 7807).
        String promoCode = request.promoCode() != null ? request.promoCode().strip() : null;
        BigDecimal rate;
        boolean promoApplied = false;
        if (promoCode != null && !promoCode.isBlank()) {
            rate = commissionRateResolver.resolve(ann.getTravelerId(), sender.getId(), promoCode);
            promoApplied = true;
        } else {
            rate = commissionRateResolver.resolve(ann.getTravelerId(), sender.getId());
        }

        BigDecimal commissionEur = netEur.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal totalEur = netEur.add(commissionEur).setScale(2, java.math.RoundingMode.HALF_UP);

        String promoLabel = null;
        if (promoApplied) {
            long pct = rate.multiply(java.math.BigDecimal.valueOf(100)).longValue();
            promoLabel = "Code " + promoCode.toUpperCase() + " : " + pct + " % de commission";
        }

        return new BidQuoteResponse(netEur, gridNet, kgNet, rate, commissionEur, totalEur, promoApplied, promoLabel);
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

        // Dedicated trip (tied to a negotiation): other senders can only bid on the
        // surplus capacity once the traveler has opened it (after the negotiating
        // sender paid). The weight cap is enforced by the weight-exceeds-capacity
        // check below since availableKg == surplus once published.
        if (announcement.isClosedToThirdPartyBids()) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "surplus-not-open",
                    "Surplus Not Open",
                    "La capacité supplémentaire de ce trajet n'est pas ouverte aux autres expéditeurs");
        }

        // Le sender réservé (celui pour qui le trajet dédié a été créé) a déjà son
        // colis dessus : il ne peut pas bidder sur le surplus de son propre trajet,
        // même une fois le surplus publié (sinon deux colis du même sender sur un trajet).
        if (announcement.isReservedSender(sender.getId())) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT, "reserved-sender-cannot-bid", "Reserved Sender Cannot Bid",
                    "Vous avez déjà un colis réservé sur ce trajet");
        }

        if (announcement.getTravelerId().equals(sender.getId())) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT, "cannot-bid-own-announcement", "Cannot Bid Own Announcement",
                    "Vous ne pouvez pas faire une demande sur votre propre annonce");
        }

        BidContentRules.assertNotRefused(announcement, request.contentCategory());

        UUID travelerId = announcement.getTravelerId();

        // Confidentialité v2 — blocage : 404 masque délibérément le blocage
        if (blockService.isBlockedEitherWay(sender.getId(), travelerId)) {
            throw new DonyBusinessException(
                    HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found",
                    "Annonce introuvable");
        }

        // Filtre contact KYC : seuls les senders vérifiés passent si la cible l'exige.
        // On ne charge le voyageur que si nécessaire (sender non vérifié).
        if (sender.getKycStatus() != KycStatus.VERIFIED) {
            UserEntity traveler = userRepository.findById(travelerId).orElse(null);
            // traveler null (suppression/race) => on laisse passer : la FK garantit normalement sa présence.
            if (traveler != null && traveler.isContactKycOnly()) {
                throw new DonyBusinessException(
                        HttpStatus.FORBIDDEN, "contact-kyc-required", "KYC Required",
                        "Cet utilisateur n'accepte que les profils vérifiés");
            }
        }

        boolean alreadyHasBid = bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
                sender.getId(), announcementId,
                List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED));
        if (alreadyHasBid) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT, "already-bid", "Demande existante",
                    "Vous avez déjà une demande en cours ou acceptée pour ce trajet");
        }

        boolean isKgFreeCreate = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
        if (!isKgFreeCreate && request.weightKg() != null
                && request.weightKg().compareTo(announcement.getAvailableKg()) > 0) {
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

        List<BidGridItemRequest> gridItems = request.gridItems() != null ? request.gridItems() : List.of();
        boolean hasGrid = !gridItems.isEmpty();
        boolean hasKg   = request.weightKg() != null && request.weightKg().compareTo(BigDecimal.ZERO) > 0;

        if (!hasGrid && !hasKg) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "bid-empty", "Bid Empty",
                "Au moins un article ou un poids doit être renseigné");
        }

        BidPricingMode bidMode = hasGrid && hasKg ? BidPricingMode.MIXED
                               : hasGrid          ? BidPricingMode.GRID
                               :                    BidPricingMode.KG;

        PaymentMethod pm;
        try {
            pm = request.paymentMethod() != null
                    ? PaymentMethod.valueOf(request.paymentMethod().toUpperCase())
                    : PaymentMethod.STRIPE;
        } catch (IllegalArgumentException e) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-payment-method", "Invalid Payment Method",
                    "Méthode de paiement inconnue : " + request.paymentMethod());
        }

        if (pm == PaymentMethod.CASH
                && !announcement.getAcceptedPaymentMethods().contains(pm)) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "cash-not-accepted", "Cash Not Accepted",
                    "Cette annonce n'accepte pas le paiement en espèces");
        }

        if ((pm == PaymentMethod.WAVE
                || pm == PaymentMethod.ORANGE_MONEY)
                && !announcement.getAcceptedPaymentMethods().contains(pm)) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "mobile-money-not-accepted", "Mobile Money Not Accepted",
                    "Cette annonce n'accepte pas le paiement " + pm.name());
        }

        if ((pm == PaymentMethod.WAVE
                || pm == PaymentMethod.ORANGE_MONEY)
                && (request.phoneNumber() == null || request.countryCode() == null)) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "mobile-money-phone-required", "Phone Required",
                    "Le numéro de téléphone et le pays sont requis pour le paiement Mobile Money");
        }

        String clientIp = resolveClientIp(httpRequest);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(sender.getId());
        bid.setWeightKg(request.weightKg());  // peut être null pour GRID mode
        bid.setPricingMode(bidMode);
        bid.setDeclaredValueEur(request.declaredValueEur());
        bid.setDescription(request.description());
        bid.setContentCategory(request.contentCategory());
        bid.setRecipientName(request.recipientName());
        bid.setRecipientPhone(request.recipientPhone());
        bid.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
        bid.setDisclaimerSignedIp(clientIp);
        bid.setPaymentMethod(pm);
        bid.setStatus(BidStatus.PENDING);

        if (pm == PaymentMethod.WAVE
                || pm == PaymentMethod.ORANGE_MONEY) {
            bid.setMobileMoneyPhone(request.phoneNumber());
            bid.setMobileMoneyCountryCode(request.countryCode());
        }

        // Code promo stocké brut (validation + rachat au moment du paiement).
        if (request.promoCode() != null && !request.promoCode().isBlank()) {
            bid.setPromoCode(request.promoCode().strip());
        }

        BidEntity saved = bidRepository.save(bid);

        auditService.log("BID", saved.getId(), "BID_CREATED", sender.getId(),
                Map.<String, Object>of(
                        "announcementId", announcementId.toString(),
                        "weightKg", saved.getWeightKg() != null ? saved.getWeightKg().toString() : "null",
                        "pricingMode", bidMode.name(),
                        "declaredValueEur", saved.getDeclaredValueEur().toString(),
                        "contentCategory", String.valueOf(saved.getContentCategory()),
                        "disclaimerSignedAt", saved.getDisclaimerSignedAt().toString(),
                        "disclaimerSignedIp", clientIp
                ));

        if (hasGrid) {
            List<BidGridItemEntity> bidGridItems = new java.util.ArrayList<>();
            for (BidGridItemRequest gReq : gridItems) {
                AnnouncementPriceGridItemEntity annItem = annGridItemRepository
                    .findById(gReq.announcementGridItemId())
                    .filter(i -> i.getAnnouncementId().equals(announcement.getId()))
                    .orElseThrow(() -> new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-grid-item", "Invalid Grid Item",
                        "Article hors grille de cette annonce : " + gReq.announcementGridItemId()));
                BidGridItemEntity bgi = new BidGridItemEntity();
                bgi.setBidId(saved.getId());
                bgi.setAnnouncementGridItemId(gReq.announcementGridItemId());
                bgi.setLabelSnapshot(annItem.getLabel());
                bgi.setUnitPriceNetSnapshot(annItem.getUnitPriceNet());
                bgi.setQuantity(gReq.quantity());
                bidGridItems.add(bgi);
            }
            bidGridItemRepository.saveAll(bidGridItems);
        }

        // Note: BidCreatedEvent is no longer published here. Traveler notification
        // happens after the sender's payment is authorized — see
        // PaymentService.promoteBidOnPaymentAuthorized().

        bidPhotoService.attachPhotos(saved.getId(), request.photoKeys());

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

    @Transactional(readOnly = true)
    public Page<BidResponse> getTravelerBids(String firebaseUid, String status, UUID announcementId, String q, int page, int size) {
        UserEntity traveler = findUserByFirebaseUid(firebaseUid);
        BidStatus bidStatus = (status != null && !status.isBlank()) ? BidStatus.valueOf(status) : null;
        String qParam = (q != null && !q.isBlank()) ? q.trim() : null;
        return bidRepository.findByTravelerIdFiltered(
                traveler.getId(), bidStatus, announcementId, qParam, PageRequest.of(page, size))
                .map(b -> toResponse(b, traveler));
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

        boolean isKgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
        if (!isKgFree && bid.getWeightKg() != null
                && bid.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
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
        if (!isKgFree && bid.getWeightKg() != null) {
            announcement.setAvailableKg(announcement.getAvailableKg().subtract(bid.getWeightKg()));
        }
        if (!isKgFree && announcement.getAvailableKg().compareTo(BigDecimal.ZERO) <= 0) {
            announcement.setStatus(AnnouncementStatus.FULL);
        }
        announcementRepository.save(announcement);
        bid.applyHandoverFrom(announcement);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "BID_ACCEPTED", traveler.getId(),
                Map.<String, Object>of("announcementId", announcement.getId().toString(),
                       "weightKg", bid.getWeightKg() != null ? bid.getWeightKg().toString() : "null"));

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

        boolean isOffPlatformPending =
                (bid.getPaymentMethod() == PaymentMethod.CASH
                 || bid.getPaymentMethod() == PaymentMethod.WAVE
                 || bid.getPaymentMethod() == PaymentMethod.ORANGE_MONEY)
                && bid.getStatus() == BidStatus.PENDING;
        if (!isOffPlatformPending) {
            requireBidStatus(bid, BidStatus.PAYMENT_ESCROWED);
        }

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
        UserEntity caller = findUserByFirebaseUid(firebaseUid);

        AnnouncementEntity announcement =
                announcementRepository.findById(bid.getAnnouncementId()).orElse(null);

        // L'annulation d'un bid avant remise est ouverte à l'expéditeur ET au
        // voyageur (qui peut se désister d'un colis déjà accepté, paiement en
        // séquestre). Le verrou D3 reste l'autorité sur les statuts annulables ;
        // l'annulation après remise (HANDED_OVER) passe par cancel-after-handover.
        boolean isSender = bid.getSenderId().equals(caller.getId());
        boolean isTraveler = announcement != null
                && announcement.getTravelerId().equals(caller.getId());
        if (!isSender && !isTraveler) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas autorisé à annuler ce bid");
        }

        if (bid.getStatus() == BidStatus.CANCELLED || bid.getStatus() == BidStatus.REJECTED
                || bid.getStatus() == BidStatus.COMPLETED) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "invalid-status", "Invalid Status",
                    "Impossible d'annuler un bid déjà terminé");
        }

        // Verrou D3 : pas d'annulation en transit ni après le départ réel (colis remis).
        com.dony.api.cancellation.CancellationGuard.assertCancellable(bid, announcement);

        // Si le bid était déjà accepté ou remis, on rend le kilo au voyageur
        // (sauf pour KG_FREE où la capacité n'est jamais décrémentée)
        if (bid.getStatus() == BidStatus.ACCEPTED || bid.getStatus() == BidStatus.HANDED_OVER) {
            if (announcement != null) {
                boolean isKgFreeCancel = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
                if (!isKgFreeCancel && bid.getWeightKg() != null) {
                    announcement.setAvailableKg(announcement.getAvailableKg().add(bid.getWeightKg()));
                }
                if (!isKgFreeCancel && announcement.getStatus() == AnnouncementStatus.FULL) {
                    announcement.setStatus(AnnouncementStatus.ACTIVE);
                }
                announcementRepository.save(announcement);
            }
        }

        bid.setStatus(BidStatus.CANCELLED);
        bidRepository.save(bid);

        String reason = isTraveler ? "CANCELLED_BY_TRAVELER" : "CANCELLED_BY_SENDER";
        auditService.log("BID", bidId, "BID_CANCELLED", caller.getId(),
                Map.of("actor", isTraveler ? "TRAVELER" : "SENDER"));

        // Rembourse l'expéditeur (séquestre libéré via RefundProcessor) et notifie,
        // quel que soit l'acteur de l'annulation.
        eventPublisher.publishEvent(new BidRejectedEvent(
                bid.getId(), bid.getSenderId(), reason));

        UserEntity senderUser = isSender
                ? caller
                : userRepository.findById(bid.getSenderId()).orElse(null);
        return toResponse(bid, senderUser);
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

    /** Upload une photo de colis pour le sender courant ; renvoie la clé S3. */
    public String uploadBidPhoto(String firebaseUid, MultipartFile file) {
        UserEntity sender = findUserByFirebaseUid(firebaseUid);
        return bidPhotoService.uploadPhoto(sender.getId(), file);
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

    private static final java.util.Set<BidStatus> PHONE_VISIBLE_STATUSES = java.util.EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.COMPLETED);

    /** Numéro révélé en clair seulement si l'offre est acceptée ou au-delà, sinon null. */
    static String phoneForStatus(String phone, BidStatus status) {
        if (phone == null) return null;
        return PHONE_VISIBLE_STATUSES.contains(status) ? phone : null;
    }

    BidResponse toResponse(BidEntity bid, UserEntity sender, UUID callerId) {
        String senderName = buildSenderName(sender);
        String senderPhone = sender != null ? phoneForStatus(sender.getPhoneNumber(), bid.getStatus()) : null;
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
        java.time.OffsetDateTime departureAt = announcement != null ? announcement.getDepartureAt() : null;
        java.math.BigDecimal pricePerKg = announcement != null ? announcement.getPricePerKg() : null;
        // Bid issu d'une négociation : prix au kilo (et donc net) figés sur le prix
        // négocié, pour ne pas dériver si l'annonce dédiée ouvre son surplus (réécrit
        // son pricePerKg) ou si le trajet lié a un tarif catalogue différent.
        if (bid.getNegotiatedNetEur() != null && bid.getWeightKg() != null
                && bid.getWeightKg().signum() > 0) {
            pricePerKg = bid.getNegotiatedNetEur()
                    .divide(bid.getWeightKg(), 2, java.math.RoundingMode.HALF_UP);
        }
        com.dony.api.matching.TransportMode transportMode = announcement != null ? announcement.getTransportMode() : null;
        String confirmationCode = (callerId != null && callerId.equals(bid.getSenderId()))
                ? bid.getConfirmationCode() : null;
        // Le code de retour n'est visible que par l'expéditeur (qui le communique au voyageur).
        String returnCode = (callerId != null && callerId.equals(bid.getSenderId()))
                ? bid.getReturnCode() : null;

        UserEntity traveler = (announcement != null)
                ? userRepository.findById(announcement.getTravelerId()).orElse(null)
                : null;
        UUID travelerId = traveler != null ? traveler.getId() : null;
        String travelerName = buildSenderName(traveler);
        String travelerPhone = traveler != null ? phoneForStatus(traveler.getPhoneNumber(), bid.getStatus()) : null;
        boolean travelerKycVerified = traveler != null
                && traveler.getKycStatus() == com.dony.api.auth.KycStatus.VERIFIED;
        boolean travelerIsProAccount = traveler != null && traveler.isProAccount();
        boolean travelerKiloPro = traveler != null && traveler.isKiloPro();
        Integer travelerTotalTrips = traveler != null ? traveler.getTotalTrips() : null;
        java.math.BigDecimal travelerAverageRating = traveler != null ? traveler.getAverageRating() : null;

        boolean senderHasRated = ratingRepository.existsByBidIdAndRaterId(bid.getId(), bid.getSenderId());
        boolean travelerHasRated = travelerId != null
                && ratingRepository.existsByBidIdAndRaterId(bid.getId(), travelerId);

        var cancellation = cancellationRepository.findByBidId(bid.getId()).orElse(null);
        String cancellationNoShowStatus = cancellation != null
                ? cancellation.getNoShowStatus().name()
                : null;
        java.time.OffsetDateTime contestationDeadline = cancellation != null
                ? cancellation.getContestationDeadline()
                : null;

        // Compute total net: sum of grid items + KG part (for display in Flutter)
        java.math.BigDecimal gridNet = bidGridItemRepository.findByBidId(bid.getId()).stream()
                .map(i -> i.getUnitPriceNetSnapshot().multiply(java.math.BigDecimal.valueOf(i.getQuantity())))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal kgNet = (bid.getWeightKg() != null && pricePerKg != null)
                ? bid.getWeightKg().multiply(pricePerKg)
                : java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalNetAmountEur = gridNet.add(kgNet)
                .setScale(2, java.math.RoundingMode.HALF_UP);

        // Montant total payé par l'EXPÉDITEUR. Modèle B (cf. PriceBreakdown) :
        // STRIPE → gross = net*(1+rate) (l'expéditeur paie net + commission) ;
        // CASH   → net (la commission est prélevée au voyageur, pas à l'expéditeur).
        // Le taux figé (commissionRate) n'existe qu'après création du paiement ;
        // avant (PENDING, pas de rate) on retombe sur le net.
        java.math.BigDecimal commissionRateSnapshot = bid.getCommissionRate();
        boolean isStripe = bid.getPaymentMethod() == null
                || bid.getPaymentMethod() == com.dony.api.payments.cash.PaymentMethod.STRIPE;
        java.math.BigDecimal totalSenderAmountEur = (isStripe && commissionRateSnapshot != null)
                ? com.dony.api.payments.PriceBreakdown.fromNet(totalNetAmountEur, commissionRateSnapshot).gross()
                : totalNetAmountEur;

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
                bid.getConfirmationCodeRefreshWindowStart(),
                cancellationNoShowStatus,
                contestationDeadline,
                bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : "STRIPE",
                bid.getPricingMode(),
                totalNetAmountEur,
                totalSenderAmountEur,
                departureAt,
                returnCode,
                bid.getReturnDeadline(),
                bid.getReturnedAt(),
                storageService.avatarUrl(sender != null ? sender.getAvatarUrl() : null),
                storageService.avatarUrl(traveler != null ? traveler.getAvatarUrl() : null),
                bidPhotoService.activePhotos(bid.getId())
        );
    }
}
