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
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.identity.VerificationSession;
import com.stripe.net.Webhook;
import com.stripe.param.identity.VerificationSessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycServiceTest {

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    KycService service;

    @BeforeEach
    void setUp() {
        service = new KycService(kycRepository, userRepository, auditService, eventPublisher);
        ReflectionTestUtils.setField(service, "webhookSecret", "whsec_test");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserEntity buildUser(KycStatus kycStatus) {
        UserEntity user = new UserEntity();
        setId(user, UUID.randomUUID());
        user.setFirebaseUid("uid-001");
        user.setPhoneNumber("+33612345678");
        user.setKycStatus(kycStatus);
        return user;
    }

    private KycVerificationEntity buildKyc(UUID userId, KycVerificationStatus status) {
        KycVerificationEntity kyc = new KycVerificationEntity();
        setId(kyc, UUID.randomUUID());
        kyc.setUserId(userId);
        kyc.setStripeVerificationSessionId("vs_test_001");
        kyc.setStatus(status);
        return kyc;
    }

    private void setId(Object entity, UUID id) {
        try {
            Class<?> clazz = entity.getClass().getSuperclass();
            Field f = clazz.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── getStatus ─────────────────────────────────────────────────────────────

    @Test
    void getStatus_userNotFound_throwsNotFoundException() {
        when(userRepository.findByFirebaseUid("unknown")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getStatus("unknown"))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void getStatus_noKycRecord_returnsPending() {
        UserEntity user = buildUser(KycStatus.PENDING);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        KycStatusResponse resp = service.getStatus("uid-001");

        assertThat(resp.kycStatus()).isEqualTo("PENDING");
        assertThat(resp.verificationStatus()).isEqualTo("PENDING");
    }

    @Test
    void getStatus_withVerifiedKycRecord_returnsVerified() {
        UserEntity user = buildUser(KycStatus.VERIFIED);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.VERIFIED);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));

        KycStatusResponse resp = service.getStatus("uid-001");

        assertThat(resp.kycStatus()).isEqualTo("VERIFIED");
        assertThat(resp.verificationStatus()).isEqualTo("VERIFIED");
    }

    // ── createSession ─────────────────────────────────────────────────────────

    @Test
    void createSession_userNotFound_throwsNotFoundException() {
        when(userRepository.findByFirebaseUid("uid-xxx")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createSession("uid-xxx"))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void createSession_alreadyVerified_throwsConflict() {
        UserEntity user = buildUser(KycStatus.VERIFIED);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.createSession("uid-001"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("déjà vérifié");
    }

    @Test
    void createSession_stripeError_throwsServiceUnavailable() {
        UserEntity user = buildUser(KycStatus.PENDING);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            vsStatic.when(() -> VerificationSession.create(any(VerificationSessionCreateParams.class)))
                    .thenThrow(new RuntimeException("Stripe unavailable"));

            assertThatThrownBy(() -> service.createSession("uid-001"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Impossible de créer la session");
        }
    }

    @Test
    void createSession_success_createsKycRecord() {
        UserEntity user = buildUser(KycStatus.PENDING);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession mockSession = mock(VerificationSession.class);
            when(mockSession.getId()).thenReturn("vs_test_new");
            when(mockSession.getUrl()).thenReturn("https://verify.stripe.com/start/vs_test_new");
            vsStatic.when(() -> VerificationSession.create(any(VerificationSessionCreateParams.class)))
                    .thenReturn(mockSession);

            KycSessionResponse resp = service.createSession("uid-001");

            assertThat(resp.sessionId()).isEqualTo("vs_test_new");
            assertThat(resp.status()).isEqualTo("PENDING");
            verify(kycRepository).save(any(KycVerificationEntity.class));
            verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_SESSION_CREATED"), any(), any());
        }
    }

    // ── processWebhook ────────────────────────────────────────────────────────

    @Test
    void processWebhook_invalidSignature_throwsBadRequest() {
        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenThrow(mock(SignatureVerificationException.class));

            assertThatThrownBy(() -> service.processWebhook("payload", "bad-sig"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Signature Stripe invalide");
        }
    }

    @Test
    void processWebhook_unhandledEventType_returnsEarlyNoException() {
        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("some.other.event");

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);

            service.processWebhook("payload", "sig"); // must not throw
        }
        verifyNoInteractions(kycRepository);
    }

    @Test
    void processWebhook_verifiedEvent_noKycRecord_returnsEarly() {
        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("identity.verification_session.verified");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getRawJson()).thenReturn("{\"id\":\"vs_unknown\"}");
        when(kycRepository.findByStripeVerificationSessionId("vs_unknown")).thenReturn(Optional.empty());

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);

            service.processWebhook("payload", "sig");
        }
        verify(kycRepository, never()).save(any());
    }

    @Test
    void processWebhook_verifiedEvent_updatesKycAndPublishesEvent() {
        UserEntity user = buildUser(KycStatus.PENDING);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.PENDING);

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("identity.verification_session.verified");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getRawJson()).thenReturn("{\"id\":\"vs_test_001\"}");
        when(kycRepository.findByStripeVerificationSessionId("vs_test_001")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);

            service.processWebhook("payload", "sig");
        }

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        verify(eventPublisher).publishEvent(any(UserKycVerifiedEvent.class));
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_VERIFIED"), any(), any());
    }

    @Test
    void processWebhook_requiresInputEvent_setsRejectedStatus() {
        UserEntity user = buildUser(KycStatus.PENDING);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.PENDING);

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("identity.verification_session.requires_input");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getRawJson()).thenReturn("{\"id\":\"vs_test_001\",\"last_error\":null}");
        when(kycRepository.findByStripeVerificationSessionId("vs_test_001")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);

            service.processWebhook("payload", "sig");
        }

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        assertThat(kyc.getRejectionReason()).isEqualTo("verification_failed");
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_REJECTED"), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void processWebhook_verifiedEvent_noUser_returnsEarly() {
        KycVerificationEntity kyc = buildKyc(UUID.randomUUID(), KycVerificationStatus.PENDING);

        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("identity.verification_session.verified");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getRawJson()).thenReturn("{\"id\":\"vs_test_001\"}");
        when(kycRepository.findByStripeVerificationSessionId("vs_test_001")).thenReturn(Optional.of(kyc));
        when(userRepository.findById(kyc.getUserId())).thenReturn(Optional.empty());

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);

            service.processWebhook("payload", "sig");
        }
        verify(kycRepository, never()).save(any());
    }
}
