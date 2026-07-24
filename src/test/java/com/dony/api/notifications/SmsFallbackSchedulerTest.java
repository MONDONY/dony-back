package com.dony.api.notifications;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmsFallbackSchedulerTest {

    @Mock NotificationRepository notificationRepository;
    @Mock UserRepository userRepository;
    @Mock SmsService smsService;
    @Mock com.dony.api.auth.FirebaseContactService firebaseContact;

    SmsFallbackScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SmsFallbackScheduler(
                notificationRepository, userRepository, smsService, firebaseContact);
    }

    /** Le numéro vit dans Firebase : on crée un user local et on y associe son contact. */
    private UserEntity userWithPhone(String uid, String phone) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        when(firebaseContact.getContact(uid))
                .thenReturn(new com.dony.api.auth.FirebaseContactService.Contact(phone, null));
        return u;
    }

    @Test
    void noPendingFallbacks_doesNothing() {
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of());

        scheduler.processPendingFallbacks();

        verifyNoInteractions(smsService, userRepository);
    }

    @Test
    void pendingFallback_sendsSmsThenMarksSmsSentAt() {
        UUID userId = UUID.randomUUID();
        var notification = criticalNotification(userId);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(notification));

        UserEntity user = userWithPhone("uid-1", "+221701234567");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        scheduler.processPendingFallbacks();

        verify(smsService).send(eq("+221701234567"), contains("[dony]"));
        assertThat(notification.getSmsSentAt()).isNotNull();
    }

    @Test
    void pendingFallback_smsTextContainsTitleAndBody() {
        UUID userId = UUID.randomUUID();
        var notification = criticalNotification(userId);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(notification));

        UserEntity user = userWithPhone("uid-1", "+221701234567");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        scheduler.processPendingFallbacks();

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(any(), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains("Livraison confirmée").contains("arrivé à destination");
    }

    @Test
    void pendingFallback_userHasNoPhone_marksHandledWithoutSms() {
        UUID userId = UUID.randomUUID();
        var notification = criticalNotification(userId);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(notification));

        // Aucun numéro côté Firebase (compte créé par email OTP par exemple)
        UserEntity user = userWithPhone("uid-sans-tel", null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        scheduler.processPendingFallbacks();

        verifyNoInteractions(smsService);
        assertThat(notification.getSmsSentAt()).isNotNull();
    }

    @Test
    void pendingFallback_userNotFound_marksHandled() {
        UUID userId = UUID.randomUUID();
        var notification = criticalNotification(userId);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(notification));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        scheduler.processPendingFallbacks();

        verifyNoInteractions(smsService);
        assertThat(notification.getSmsSentAt()).isNotNull();
    }

    @Test
    void multiplePendingFallbacks_processesAll() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        var n1 = criticalNotification(userId1);
        var n2 = criticalNotification(userId2);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(n1, n2));

        UserEntity u1 = userWithPhone("uid-1", "+221111111111");
        UserEntity u2 = userWithPhone("uid-2", "+221222222222");
        when(userRepository.findById(userId1)).thenReturn(Optional.of(u1));
        when(userRepository.findById(userId2)).thenReturn(Optional.of(u2));

        scheduler.processPendingFallbacks();

        verify(smsService, times(2)).send(anyString(), anyString());
    }

    @Test
    void smsServiceThrows_doesNotPropagateAndContinues() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        var n1 = criticalNotification(userId1);
        var n2 = criticalNotification(userId2);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(n1, n2));

        UserEntity u = userWithPhone("uid-1", "+221111111111");
        when(userRepository.findById(any())).thenReturn(Optional.of(u));
        doThrow(new RuntimeException("SMS provider down")).when(smsService).send(anyString(), anyString());

        // Must not throw
        scheduler.processPendingFallbacks();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private NotificationEntity criticalNotification(UUID userId) {
        return new NotificationEntity(userId, "DELIVERY_CONFIRMED",
                "Livraison confirmée", "Votre colis est arrivé à destination",
                Map.of("type", "DELIVERY_CONFIRMED"), true);
    }
}