package com.dony.api.kyc;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.kyc.dto.KycSessionResponse;
import com.dony.api.kyc.dto.KycStatusResponse;
import com.dony.api.kyc.events.UserKycVerifiedEvent;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.StripeObject;
import com.stripe.model.identity.VerificationSession;
import com.stripe.net.Webhook;
import com.stripe.model.Event;
import com.stripe.param.identity.VerificationSessionCreateParams;
import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

@Service
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final KycRepository kycRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    public KycService(KycRepository kycRepository,
                      UserRepository userRepository,
                      AuditService auditService,
                      ApplicationEventPublisher eventPublisher) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public KycSessionResponse createSession(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyNotFoundException("Utilisateur introuvable"));

        if (user.getKycStatus() == KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KYC déjà vérifié");
        }

        try {
            VerificationSessionCreateParams params = VerificationSessionCreateParams.builder()
                    .setType(VerificationSessionCreateParams.Type.DOCUMENT)
                    .setReturnUrl("https://dony.app/kyc/complete")
                    .putMetadata("user_id", user.getId().toString())
                    .setOptions(
                            VerificationSessionCreateParams.Options.builder()
                                    .setDocument(
                                            VerificationSessionCreateParams.Options.Document.builder()
                                                    .setRequireLiveCapture(true)
                                                    .setRequireMatchingSelfie(true)
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();

            VerificationSession session = VerificationSession.create(params);

            // Find existing or create new KYC record
            KycVerificationEntity kyc = kycRepository.findByUserId(user.getId())
                    .orElseGet(() -> {
                        KycVerificationEntity newKyc = new KycVerificationEntity();
                        newKyc.setUserId(user.getId());
                        return newKyc;
                    });

            kyc.setStripeVerificationSessionId(session.getId());
            kyc.setStatus(KycVerificationStatus.PENDING);
            kyc.setRejectionReason(null);
            kycRepository.save(kyc);

            auditService.log("kyc_verification", kyc.getId(), "KYC_SESSION_CREATED",
                    user.getId(), Map.of("sessionId", session.getId()));

            return new KycSessionResponse(session.getUrl(), session.getId(), "PENDING");

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create Stripe Identity session for user {}", user.getId(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Impossible de créer la session de vérification");
        }
    }

    @Transactional(readOnly = true)
    public KycStatusResponse getStatus(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyNotFoundException("Utilisateur introuvable"));

        Optional<KycVerificationEntity> kyc = kycRepository.findByUserId(user.getId());

        String verificationStatus = kyc
                .map(k -> k.getStatus().name())
                .orElse("PENDING");

        return new KycStatusResponse(user.getKycStatus().name(), verificationStatus);
    }

    @Transactional
    public void processWebhook(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature: {}", e.getMessage());
            Sentry.captureException(e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Signature Stripe invalide");
        }

        String eventType = event.getType();
        log.info("Stripe Identity webhook received: {}", eventType);

        if (!"identity.verification_session.verified".equals(eventType) &&
                !"identity.verification_session.requires_input".equals(eventType)) {
            return;
        }

        Optional<StripeObject> stripeObjectOpt = event.getDataObjectDeserializer().getObject();
        if (stripeObjectOpt.isEmpty()) {
            log.warn("Could not deserialize Stripe webhook payload for event {}", eventType);
            return;
        }

        VerificationSession session = (VerificationSession) stripeObjectOpt.get();
        String sessionId = session.getId();

        KycVerificationEntity kyc = kycRepository.findByStripeVerificationSessionId(sessionId)
                .orElse(null);

        if (kyc == null) {
            log.warn("No KYC record found for session {}", sessionId);
            return;
        }

        UserEntity user = userRepository.findById(kyc.getUserId())
                .orElse(null);

        if (user == null) {
            log.warn("No user found for KYC record {}", kyc.getId());
            return;
        }

        if ("identity.verification_session.verified".equals(eventType)) {
            kyc.setStatus(KycVerificationStatus.VERIFIED);
            user.setKycStatus(KycStatus.VERIFIED);

            auditService.log("kyc_verification", kyc.getId(), "KYC_VERIFIED",
                    user.getId(), Map.of("sessionId", sessionId));

            eventPublisher.publishEvent(new UserKycVerifiedEvent(user.getId(), user.getPhoneNumber()));

        } else {
            // requires_input → rejected
            kyc.setStatus(KycVerificationStatus.REQUIRES_INPUT);
            kyc.setRejectionReason(extractRejectionReason(session));
            user.setKycStatus(KycStatus.REJECTED);

            auditService.log("kyc_verification", kyc.getId(), "KYC_REJECTED",
                    user.getId(), Map.of("sessionId", sessionId,
                            "reason", kyc.getRejectionReason() != null ? kyc.getRejectionReason() : "unknown"));
        }

        kycRepository.save(kyc);
        userRepository.save(user);
    }

    private String extractRejectionReason(VerificationSession session) {
        try {
            if (session.getLastError() != null) {
                return session.getLastError().getReason();
            }
        } catch (Exception ignored) {
        }
        return "requires_input";
    }
}
