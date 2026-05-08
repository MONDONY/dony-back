package com.dony.api.auth;

import com.dony.api.auth.events.UserFinalizedEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.StorageService;
import com.dony.api.kyc.KycRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(AccountFinalizationService.class);

    private final UserRepository userRepository;
    private final KycRepository kycRepository;
    private final StorageService storageService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    public AccountFinalizationService(UserRepository userRepository,
                                      KycRepository kycRepository,
                                      StorageService storageService,
                                      ApplicationEventPublisher eventPublisher,
                                      AuditService auditService) {
        this.userRepository = userRepository;
        this.kycRepository = kycRepository;
        this.storageService = storageService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional
    public void finalize(UserEntity user, FinalizationReason reason) {
        UUID userId = user.getId();
        String uid = userId.toString();

        // 1. Pseudonymise personal data
        user.setEmail("deleted_" + uid + "@dony.app");
        user.setPhoneNumber("+00000000000");
        user.setFirstName("Utilisateur");
        user.setLastName("supprimé");
        user.setBirthDate(null);
        user.setCity(null);
        user.setFcmToken(null);
        user.setStatus(UserStatus.BANNED);
        user.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRepository.save(user);

        // 2. Soft-delete KYC
        kycRepository.findByUserId(userId).ifPresent(kyc -> {
            kyc.softDelete();
            kycRepository.save(kyc);
        });

        // 3. Delete Cloudflare R2 files
        storageService.deleteByPrefix("kyc/" + userId + "/");

        // 4. Publish event → Firestore + Stripe cleanup (cross-package via events)
        eventPublisher.publishEvent(new UserFinalizedEvent(userId, reason));

        // 5. Delete Firebase user
        try {
            FirebaseAuth.getInstance().deleteUser(user.getFirebaseUid());
        } catch (FirebaseAuthException e) {
            log.warn("Firebase deleteUser failed for {}: {}", userId, e.getMessage());
        }

        // 6. Immutable audit entry
        auditService.log("USER", userId, "USER_GDPR_DELETION", userId,
                Map.of("reason", reason.name(), "pseudonymized", true));
        log.info("Account finalized for user {} (reason: {})", uid, reason);
    }
}
