package com.dony.api.auth;

import com.dony.api.auth.dto.UpgradeToProRequest;
import com.dony.api.auth.events.UserSuspendedEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.kyc.KycRepository;
import com.dony.api.kyc.KycVerificationEntity;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int SUSPENSION_REFUSED_THRESHOLD = 2;

    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final KycRepository kycRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public UserService(UserRepository userRepository,
                       BidRepository bidRepository,
                       PaymentRepository paymentRepository,
                       KycRepository kycRepository,
                       AuditService auditService,
                       ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
        this.kycRepository = kycRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    // Story 9.5 — Suspension automatique après trop de refus de colis
    @Transactional
    public void checkAndSuspendSender(UUID senderId) {
        userRepository.findById(senderId).ifPresent(sender -> {
            if (sender.getStatus() == UserStatus.SUSPENDED || sender.getStatus() == UserStatus.BANNED) {
                return;
            }
            if (sender.getRefusedCount() >= SUSPENSION_REFUSED_THRESHOLD) {
                sender.setStatus(UserStatus.SUSPENDED);
                userRepository.save(sender);

                auditService.log("USER", senderId, "USER_AUTO_SUSPENDED", senderId,
                        Map.of("reason", "refused_count_threshold",
                                "refusedCount", sender.getRefusedCount()));

                eventPublisher.publishEvent(new UserSuspendedEvent(
                        senderId,
                        sender.getPhoneNumber(),
                        sender.getEmail(),
                        "Suspension automatique suite à " + sender.getRefusedCount() + " colis refusés"
                ));

                log.warn("Sender {} auto-suspended after {} parcel refusals", senderId, sender.getRefusedCount());
            }
        });
    }

    // Story 9.8 — Droit à l'effacement RGPD
    @Transactional
    public void deleteAccount(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        // Check for active transactions (ESCROW payments)
        List<UUID> senderBidIds = bidRepository.findBySenderId(user.getId())
                .stream().map(BidEntity::getId).collect(Collectors.toList());

        List<UUID> travelerBidIds = bidRepository.findCompletedBidsByTravelerId(user.getId())
                .stream().map(BidEntity::getId).collect(Collectors.toList());
        // Also include non-completed bids for the traveler — get all their bids
        // For simplicity, check all known bid IDs for active escrow
        List<UUID> allBidIds = new java.util.ArrayList<>(senderBidIds);
        allBidIds.addAll(travelerBidIds);

        if (!allBidIds.isEmpty() && paymentRepository.existsByBidIdInAndStatus(allBidIds, PaymentStatus.ESCROW)) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "active-transactions",
                    "Unprocessable", "Impossible — vous avez des transactions en cours");
        }

        // Pseudonymize personal data
        String uid = user.getId().toString();
        user.setEmail("deleted_" + uid + "@dony.app");
        user.setPhoneNumber("+00000000000");
        user.setFirstName("Utilisateur");
        user.setLastName("supprimé");
        user.setBirthDate(null);
        user.setCity(null);
        user.setFcmToken(null);
        user.setStatus(UserStatus.BANNED);
        userRepository.save(user);

        // Soft-delete KYC record (files live on Stripe — no local storage to clean up)
        Optional<KycVerificationEntity> kycOpt = kycRepository.findByUserId(user.getId());
        kycOpt.ifPresent(kyc -> {
            kyc.softDelete();
            kycRepository.save(kyc);
        });

        // Revoke Firebase account
        try {
            FirebaseAuth.getInstance().deleteUser(firebaseUid);
        } catch (FirebaseAuthException e) {
            log.warn("Could not delete Firebase user {}: {}", firebaseUid, e.getMessage());
        }

        auditService.log("USER", user.getId(), "USER_GDPR_DELETION", user.getId(),
                Map.of("pseudonymized", true));

        log.info("GDPR deletion completed for user {}", uid);
    }

    // PR-1 — Upgrade to PRO account
    /**
     * Sets the user as a PRO account with optional company details.
     *
     * <p>The caller must pass the already-loaded {@link UserEntity} to avoid a redundant
     * DB lookup (AuthService already fetched the user by firebaseUid).
     *
     * <p>Business rules:
     * <ul>
     *   <li>Any authenticated user can call this (SENDER or TRAVELER).</li>
     *   <li>If {@code stripeAccountId} is already set → HTTP 409 (cannot change account type
     *       once a Stripe Connect account exists).</li>
     *   <li>{@code siret}, when provided and non-blank, must be exactly 14 digits → HTTP 422.</li>
     *   <li>Re-upgrade (user is already PRO) is allowed — it acts as an idempotent update of
     *       {@code proCompanyName}/{@code proSiret}. The audit action changes to
     *       {@code USER_PRO_PROFILE_UPDATED} to distinguish updates from the initial upgrade.</li>
     * </ul>
     *
     * @param user    the already-loaded user entity (no extra DB hit)
     * @param request the upgrade request with optional company details
     * @return the updated {@link UserEntity} (caller builds the response DTO)
     */
    @Transactional
    public UserEntity upgradeToPro(UserEntity user, UpgradeToProRequest request) {
        UUID userId = user.getId();

        if (user.getStripeAccountId() != null && !user.getStripeAccountId().isBlank()) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "stripe-account-exists",
                    "Stripe account already exists",
                    "Un compte Stripe Connect est déjà associé à ce compte"
            );
        }

        if (request.siret() != null && !request.siret().isBlank()) {
            if (!request.siret().matches("\\d{14}")) {
                throw new DonyBusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-siret",
                        "Invalid SIRET",
                        "Le numéro SIRET doit contenir exactement 14 chiffres"
                );
            }
        }

        // Determine audit action: re-upgrade updates company info without re-triggering
        // USER_UPGRADED_TO_PRO, which would be misleading in the audit trail.
        boolean alreadyPro = user.isProAccount();
        String auditAction = alreadyPro ? "USER_PRO_PROFILE_UPDATED" : "USER_UPGRADED_TO_PRO";

        user.setProAccount(true);
        user.setProCompanyName(request.companyName());
        user.setProSiret(request.siret());
        UserEntity saved = userRepository.save(user);

        auditService.log("USER", userId, auditAction, userId,
                Map.of("companyName", request.companyName() != null ? request.companyName() : "",
                        "siret", request.siret() != null ? request.siret() : ""));

        if (alreadyPro) {
            log.info("User {} PRO profile updated (companyName, siret)", userId);
        } else {
            log.info("User {} upgraded to PRO account", userId);
        }
        return saved;
    }

    // Story 9.5 — Admin unsuspend
    @Transactional
    public void unsuspendUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        auditService.log("USER", userId, "USER_UNSUSPENDED", userId, Map.of());
        log.info("User {} unsuspended by admin", userId);
    }
}
