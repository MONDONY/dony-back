package com.dony.api.matching;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.BidCheckoutRequest;
import com.dony.api.matching.dto.BidCheckoutResponse;
import com.dony.api.payments.PaymentService;
import com.dony.api.payments.dto.CreatePaymentRequest;
import com.dony.api.payments.dto.PaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class BidCheckoutService {

    static final int AWAITING_PAYMENT_GRACE_MINUTES = 15;

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final PaymentService paymentService;
    private final String stripePublishableKey;

    public BidCheckoutService(BidRepository bidRepository,
                              AnnouncementRepository announcementRepository,
                              UserRepository userRepository,
                              AuditService auditService,
                              PaymentService paymentService,
                              @Value("${stripe.publishable-key:}") String stripePublishableKey) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.paymentService = paymentService;
        this.stripePublishableKey = stripePublishableKey;
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public BidCheckoutResponse checkout(String firebaseUid,
                                       BidCheckoutRequest req,
                                       HttpServletRequest httpRequest) {

        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
            .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!sender.getRoles().contains(Role.SENDER)) {
            sender.getRoles().add(Role.SENDER);
            userRepository.save(sender);
        }

        AnnouncementEntity announcement = announcementRepository.findById(req.announcementId())
            .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                "announcement-not-active", "Announcement Not Active",
                "Cette annonce n'est plus disponible");
        }

        if (announcement.getTravelerId().equals(sender.getId())) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                "cannot-bid-own-announcement", "Cannot Bid Own Announcement",
                "Vous ne pouvez pas faire une demande sur votre propre annonce");
        }

        boolean alreadyHasBid = bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
            sender.getId(), announcement.getId(),
            List.of(BidStatus.AWAITING_PAYMENT, BidStatus.PENDING, BidStatus.ACCEPTED));
        if (alreadyHasBid) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                "already-bid", "Demande existante",
                "Vous avez déjà une demande en cours pour ce trajet");
        }

        if (req.weightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "weight-exceeds-capacity", "Weight Exceeds Capacity",
                "Poids demandé supérieur à la capacité disponible");
        }

        if (req.declaredValueEur().compareTo(BigDecimal.valueOf(500)) > 0) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "value-exceeds-limit", "Value Exceeds Limit",
                "Valeur maximum : 500 €");
        }

        String ip = resolveClientIp(httpRequest);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(req.weightKg());
        bid.setDeclaredValueEur(req.declaredValueEur());
        bid.setDescription(req.description());
        bid.setContentCategory(req.contentCategory());
        bid.setRecipientName(req.recipientName());
        bid.setRecipientPhone(req.recipientPhone());
        bid.setDisclaimerSignedAt(now);
        bid.setDisclaimerSignedIp(ip);
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setAwaitingPaymentExpiresAt(now.plusMinutes(AWAITING_PAYMENT_GRACE_MINUTES));

        BidEntity saved = bidRepository.save(bid);

        // Délégation à PaymentService — crée le PaymentIntent Stripe
        CreatePaymentRequest paymentReq = new CreatePaymentRequest();
        paymentReq.setBidId(saved.getId());
        PaymentResponse paymentResp = paymentService.createEscrow(paymentReq, firebaseUid);

        // Backfill paymentIntentId on the bid so schedulers can find it
        saved.setPaymentIntentId(paymentResp.getStripePaymentIntentId());
        bidRepository.save(saved);

        return new BidCheckoutResponse(
            saved.getId(),
            paymentResp.getClientSecret(),
            stripePublishableKey,
            saved.getAwaitingPaymentExpiresAt()
        );
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
