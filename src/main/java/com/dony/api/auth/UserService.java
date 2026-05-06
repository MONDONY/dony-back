package com.dony.api.auth;

import com.dony.api.auth.dto.UpgradeToProRequest;
import com.dony.api.auth.events.AccountDeletionRequestedEvent;
import com.dony.api.auth.events.UserSuspendedEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.kyc.KycRepository;
import com.dony.api.kyc.KycVerificationEntity;
import com.dony.api.payments.PaymentRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int SUSPENSION_REFUSED_THRESHOLD = 2;

    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final KycRepository kycRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public UserService(UserRepository userRepository,
                       PaymentRepository paymentRepository,
                       KycRepository kycRepository,
                       AuditService auditService,
                       ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
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

    // Story 9.8 — Droit à l'effacement RGPD — demande initiale (période de grâce 30 jours)
    @Transactional
    public void requestDeletion(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        if (user.getStatus() == UserStatus.PENDING_DELETION) {
            return;
        }

        if (paymentRepository.hasActiveEscrowForUser(user.getId())) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "active-transactions",
                    "Unprocessable", "Impossible — vous avez des transactions en cours");
        }

        user.setStatus(UserStatus.PENDING_DELETION);
        user.setDeletionRequestedAt(Instant.now());
        userRepository.save(user);

        eventPublisher.publishEvent(new AccountDeletionRequestedEvent(user.getId()));
        log.info("Account deletion requested for user {}", user.getId());
    }

    // Story 9.8 — Annulation de la demande de suppression (dans les 30 jours)
    @Transactional
    public void reactivateAccount(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        if (user.getStatus() != UserStatus.PENDING_DELETION) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "not-pending-deletion",
                    "Conflict", "Ce compte n'est pas en cours de suppression");
        }

        user.setStatus(UserStatus.ACTIVE);
        user.setDeletionRequestedAt(null);
        userRepository.save(user);

        auditService.log("USER", user.getId(), "USER_DELETION_CANCELLED", user.getId(), Map.of());
        log.info("Account deletion cancelled for user {}", user.getId());
    }

    // Story 9.8 — Finalisation RGPD à J+30 (appelé par le scheduler)
    @Transactional
    public void finalizeGdprDeletion(UserEntity user) {
        String uid = user.getId().toString();

        // 1. Pseudonymise personal data
        user.setEmail("deleted_" + uid + "@dony.app");
        user.setPhoneNumber("+00000000000");
        user.setFirstName("Utilisateur");
        user.setLastName("supprimé");
        user.setBirthDate(null);
        user.setCity(null);
        user.setFcmToken(null);
        user.setStatus(UserStatus.BANNED);

        // 2. Soft-delete KYC BEFORE flushing the user (RGPD safety + Hibernate ordering)
        Optional<KycVerificationEntity> kycOpt = kycRepository.findByUserId(user.getId());
        kycOpt.ifPresent(kyc -> {
            kyc.softDelete();
            kycRepository.save(kyc);
        });

        // 3. Soft-delete the user and flush to DB
        user.softDelete();
        userRepository.save(user);

        // 4. Remove Firebase identity
        try {
            FirebaseAuth.getInstance().deleteUser(user.getFirebaseUid());
        } catch (FirebaseAuthException e) {
            log.warn("Could not delete Firebase user {}: {}", user.getFirebaseUid(), e.getMessage());
        }

        // 5. Immutable audit entry
        auditService.log("USER", user.getId(), "USER_GDPR_DELETION", user.getId(),
                Map.of("pseudonymized", true));
        log.info("GDPR deletion finalized for user {}", uid);
    }

    // Story 9.8 — Méthode conservée pour compatibilité avec AuthService (délègue à requestDeletion)
    @Transactional
    public void deleteAccount(String firebaseUid) {
        requestDeletion(firebaseUid);
    }

    // PR-1 — Upgrade to PRO account
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
