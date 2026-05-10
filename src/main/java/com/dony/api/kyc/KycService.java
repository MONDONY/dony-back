package com.dony.api.kyc;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.common.ProcessedStripeEvent;
import com.dony.api.common.ProcessedStripeEventRepository;
import com.dony.api.kyc.dto.KycSessionResponse;
import com.dony.api.kyc.dto.KycStatusResponse;
import com.dony.api.kyc.events.UserKycVerifiedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
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
    private final ProcessedStripeEventRepository processedStripeEventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    public KycService(KycRepository kycRepository,
                      UserRepository userRepository,
                      AuditService auditService,
                      ApplicationEventPublisher eventPublisher,
                      ProcessedStripeEventRepository processedStripeEventRepository) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.processedStripeEventRepository = processedStripeEventRepository;
    }

    @Transactional
    public KycSessionResponse createSession(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyNotFoundException("Utilisateur introuvable"));

        if (user.getKycStatus() == KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KYC déjà vérifié");
        }

        // Idempotency: return existing session if already PENDING to avoid duplicate Stripe sessions
        if (user.getKycStatus() == KycStatus.PENDING) {
            Optional<KycVerificationEntity> existing = kycRepository.findByUserId(user.getId());
            if (existing.isPresent() && existing.get().getStripeVerificationSessionId() != null) {
                String existingSessionId = existing.get().getStripeVerificationSessionId();
                try {
                    VerificationSession existingSession = VerificationSession.retrieve(existingSessionId);
                    return new KycSessionResponse(existingSession.getUrl(), existingSessionId, "PENDING");
                } catch (Exception e) {
                    log.warn("Could not retrieve existing KYC session {}, creating new one", existingSessionId);
                }
            }
        }

        // Transition NOT_STARTED → PENDING when session is created
        if (user.getKycStatus() == KycStatus.NOT_STARTED) {
            user.setKycStatus(KycStatus.PENDING);
            userRepository.save(user);
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

    @Transactional
    public void abandonSession(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyNotFoundException("Utilisateur introuvable"));

        if (user.getKycStatus() != KycStatus.PENDING) return;

        user.setKycStatus(KycStatus.NOT_STARTED);
        userRepository.save(user);

        auditService.log("kyc_verification", user.getId(), "KYC_SESSION_ABANDONED",
                user.getId(), Map.of("reason", "user_closed_webview"));
    }

    @Transactional(readOnly = true)
    public KycStatusResponse getStatus(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyNotFoundException("Utilisateur introuvable"));

        Optional<KycVerificationEntity> kyc = kycRepository.findByUserId(user.getId());

        String verificationStatus = kyc
                .map(k -> k.getStatus().name())
                .orElse("NOT_STARTED");

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

        // Idempotency: skip already-processed events
        if (processedStripeEventRepository.existsByEventId(event.getId())) {
            log.info("Stripe KYC event {} already processed — skipping", event.getId());
            return;
        }
        processedStripeEventRepository.save(new ProcessedStripeEvent(event.getId()));

        if (!"identity.verification_session.verified".equals(eventType) &&
                !"identity.verification_session.requires_input".equals(eventType) &&
                !"identity.verification_session.canceled".equals(eventType)) {
            return;
        }

        // Extract sessionId from raw JSON to avoid Stripe SDK API version mismatch
        String rawJson = event.getDataObjectDeserializer().getRawJson();
        String sessionId;
        String lastErrorReason = null;
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode idNode = root.path("id");
            if (idNode.isMissingNode() || idNode.isNull()) {
                log.warn("Stripe webhook missing session id for event {}", eventType);
                return;
            }
            sessionId = idNode.asText();
            JsonNode lastError = root.path("last_error");
            if (!lastError.isMissingNode() && !lastError.isNull()) {
                lastErrorReason = lastError.path("reason").asText(null);
            }
        } catch (Exception e) {
            log.warn("Could not parse Stripe webhook payload for event {}: {}", eventType, e.getMessage());
            return;
        }

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
            // Idempotency: only update if not already VERIFIED
            if (kyc.getStatus() != KycVerificationStatus.VERIFIED) {
                kyc.setStatus(KycVerificationStatus.VERIFIED);
                user.setKycStatus(KycStatus.VERIFIED);
                kycRepository.save(kyc);
                userRepository.save(user);

                auditService.log("kyc_verification", kyc.getId(), "KYC_VERIFIED",
                        user.getId(), Map.of("sessionId", sessionId));

                eventPublisher.publishEvent(new UserKycVerifiedEvent(user.getId(), user.getPhoneNumber()));
            }

        } else if ("identity.verification_session.canceled".equals(eventType)) {
            // Session abandonnée avant soumission des documents → NOT_STARTED
            // L'utilisateur n'a pas tenté la vérification ; on lui permet de recommencer proprement.
            kyc.setStatus(KycVerificationStatus.REJECTED);
            kyc.setRejectionReason("session_canceled");
            user.setKycStatus(KycStatus.NOT_STARTED);
            kycRepository.save(kyc);
            userRepository.save(user);

            auditService.log("kyc_verification", kyc.getId(), "KYC_CANCELED",
                    user.getId(), Map.of("sessionId", sessionId, "reason", "session_canceled"));

        } else {
            // requires_input → documents soumis mais refusés par Stripe
            kyc.setStatus(KycVerificationStatus.REJECTED);
            kyc.setRejectionReason(lastErrorReason != null ? lastErrorReason : "verification_failed");
            user.setKycStatus(KycStatus.REJECTED);
            kycRepository.save(kyc);
            userRepository.save(user);

            auditService.log("kyc_verification", kyc.getId(), "KYC_REJECTED",
                    user.getId(), Map.of("sessionId", sessionId,
                            "reason", kyc.getRejectionReason()));
        }
    }
}
