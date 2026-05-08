package com.dony.api.auth;

import com.dony.api.auth.events.UserFinalizedEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.StorageService;
import com.dony.api.kyc.KycRepository;
import com.dony.api.kyc.KycVerificationEntity;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountFinalizationService")
class AccountFinalizationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private KycRepository kycRepository;
    @Mock private StorageService storageService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;

    @InjectMocks private AccountFinalizationService service;

    private UserEntity makeUser() {
        UserEntity u = new UserEntity();
        setId(u, UUID.randomUUID());
        u.setFirebaseUid("uid-test");
        u.setEmail("test@example.com");
        u.setPhoneNumber("+33600000001");
        u.setFirstName("Jean");
        u.setLastName("Dupont");
        u.setStatus(UserStatus.PENDING_DELETION);
        u.setBirthDate(java.time.LocalDate.of(1990, 1, 1));
        u.setCity("Paris");
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try { Field f = c.getDeclaredField("id"); f.setAccessible(true); f.set(entity, id); return; }
                catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    @DisplayName("pseudonymise le user et soft-delete KYC")
    void pseudonymizesUserAndSoftDeletesKyc() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        KycVerificationEntity kyc = new KycVerificationEntity();
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kyc));
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        assertThat(user.getStatus()).isEqualTo(UserStatus.BANNED);
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getEmail()).startsWith("deleted_");
        assertThat(user.getPhoneNumber()).isEqualTo("+00000000000");
        assertThat(user.getFirstName()).isEqualTo("Utilisateur");
        assertThat(user.getLastName()).isEqualTo("supprimé");
        assertThat(user.getFcmToken()).isNull();
        assertThat(kyc.getDeletedAt()).isNotNull();
        assertThat(user.getBirthDate()).isNull();
        assertThat(user.getCity()).isNull();
    }

    @Test
    @DisplayName("supprime les fichiers R2 du user")
    void deletesR2Files() {
        UserEntity user = makeUser();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        verify(storageService).deleteByPrefix("kyc/" + user.getId() + "/");
    }

    @Test
    @DisplayName("publie UserFinalizedEvent avec la bonne reason")
    void publishesUserFinalizedEvent() {
        UserEntity user = makeUser();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.SOFT_GRACE_EXPIRED);
        }

        ArgumentCaptor<UserFinalizedEvent> captor = ArgumentCaptor.forClass(UserFinalizedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo(FinalizationReason.SOFT_GRACE_EXPIRED);
    }

    @Test
    @DisplayName("crée une entrée audit log USER_GDPR_DELETION")
    void createsAuditLog() {
        UserEntity user = makeUser();
        UUID userId = user.getId();
        when(kycRepository.findByUserId(any())).thenReturn(Optional.empty());
        com.google.firebase.auth.FirebaseAuth mockAuth = mock(com.google.firebase.auth.FirebaseAuth.class);

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class)) {
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockAuth);
            service.finalize(user, FinalizationReason.HARD_IMMEDIATE);
        }

        verify(auditService).log(eq("USER"), eq(userId), eq("USER_GDPR_DELETION"), eq(userId), any());
    }
}
