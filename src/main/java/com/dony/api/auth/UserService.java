package com.dony.api.auth;

import com.dony.api.auth.dto.UpgradeToProRequest;
import com.dony.api.auth.events.AccountDeletionRequestedEvent;
import com.dony.api.auth.events.UserSuspendedEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.payments.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int SUSPENSION_REFUSED_THRESHOLD = 2;

    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final AccountFinalizationService accountFinalizationService;

    public UserService(UserRepository userRepository,
                       PaymentRepository paymentRepository,
                       AuditService auditService,
                       ApplicationEventPublisher eventPublisher,
                       AccountFinalizationService accountFinalizationService) {
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.accountFinalizationService = accountFinalizationService;
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

    // Admin — override du taux de commission Dony d'un utilisateur (null = retour au taux global).
    @Transactional
    public void setCommissionRateOverride(UUID userId, java.math.BigDecimal rate) {
        if (rate != null && (rate.signum() < 0 || rate.compareTo(java.math.BigDecimal.ONE) >= 0)) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-commission-rate",
                    "Invalid Commission Rate", "Le taux doit être dans [0, 1[ ou null (taux global)");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found",
                        "Not Found", "Utilisateur introuvable"));
        user.setCommissionRateOverride(rate);
        userRepository.save(user);
        auditService.log("USER", user.getId(), "USER_COMMISSION_RATE_OVERRIDE_SET", user.getId(),
                Map.of("rate", rate == null ? "global" : rate.toPlainString()));
    }

    // Story 9.8 — Finalisation RGPD à J+30 (appelé par le scheduler)
    @Transactional
    public void finalizeGdprDeletion(UserEntity user) {
        accountFinalizationService.finalize(user, FinalizationReason.SOFT_GRACE_EXPIRED);
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

        if (!alreadyPro) {
            eventPublisher.publishEvent(new UserProStatusChangedEvent(userId, true));
            log.info("User {} upgraded to PRO account", userId);
        } else {
            log.info("User {} PRO profile updated (companyName, siret)", userId);
        }
        return saved;
    }

    @Transactional
    public UserEntity downgradePro(UserEntity user) {
        UUID userId = user.getId();
        user.setProAccount(false);
        user.setProCompanyName(null);
        user.setProSiret(null);
        UserEntity saved = userRepository.save(user);
        auditService.log("USER", userId, "USER_DOWNGRADED_FROM_PRO", userId, Map.of());
        eventPublisher.publishEvent(new UserProStatusChangedEvent(userId, false));
        log.info("User {} downgraded from PRO account", userId);
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
