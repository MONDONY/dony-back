package com.dony.api.notifications;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

    @Mock UserRepository userRepository;
    @Mock NotificationPrefsService notificationPrefsService;
    @InjectMocks FcmService service;

    private static final UUID USER_ID = UUID.randomUUID();

    // Test 1: isAllowed() retourne false → sendToUser() retourne false sans appel à userRepository
    @Test
    void sendToUser_prefDisabled_returnsFalseWithoutCallingUserRepo() {
        when(notificationPrefsService.isAllowed(USER_ID, "BID_CREATED")).thenReturn(false);

        boolean result = service.sendToUser(USER_ID, "Titre", "Corps",
                Map.of("type", "BID_CREATED"));

        assertThat(result).isFalse();
        verifyNoInteractions(userRepository);
    }

    // Test 2: isAllowed() retourne true → la méthode continue et appelle userRepository.findById()
    @Test
    void sendToUser_prefEnabled_proceedsToUserRepository() {
        when(notificationPrefsService.isAllowed(USER_ID, "BID_CREATED")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        boolean result = service.sendToUser(USER_ID, "Titre", "Corps",
                Map.of("type", "BID_CREATED"));

        assertThat(result).isFalse(); // false car user not found
        verify(userRepository).findById(USER_ID);
    }

    // Test 3: data est null → isAllowed() est appelé avec null comme type
    @Test
    void sendToUser_dataNullType_isAllowedCalledWithNull() {
        when(notificationPrefsService.isAllowed(USER_ID, null)).thenReturn(false);

        boolean result = service.sendToUser(USER_ID, "Titre", "Corps", null);

        assertThat(result).isFalse();
        verify(notificationPrefsService).isAllowed(USER_ID, null);
        verifyNoInteractions(userRepository);
    }

    // Test 4: data sans clé "type" → isAllowed() appelé avec null (get() retourne null si absent)
    @Test
    void sendToUser_dataWithoutTypeKey_isAllowedCalledWithNull() {
        when(notificationPrefsService.isAllowed(USER_ID, null)).thenReturn(false);

        boolean result = service.sendToUser(USER_ID, "Titre", "Corps",
                Map.of("other_key", "value"));

        assertThat(result).isFalse();
        verify(notificationPrefsService).isAllowed(USER_ID, null);
        verifyNoInteractions(userRepository);
    }

    // Test 5: type critique (PAYMENT_RELEASED) → isAllowed() est appelé (le service décide lui-même)
    @Test
    void sendToUser_criticalType_isAllowedIsCalled() {
        when(notificationPrefsService.isAllowed(USER_ID, "PAYMENT_RELEASED")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.sendToUser(USER_ID, "Paiement libéré", "Votre paiement a été libéré",
                Map.of("type", "PAYMENT_RELEASED"));

        verify(notificationPrefsService).isAllowed(USER_ID, "PAYMENT_RELEASED");
        verify(userRepository).findById(USER_ID);
    }

    // Test 6: user sans token FCM → retourne false même si pref autorisée
    @Test
    void sendToUser_prefEnabled_noFcmToken_returnsFalse() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setFcmToken(null);

        when(notificationPrefsService.isAllowed(USER_ID, "NEW_MESSAGE")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        boolean result = service.sendToUser(USER_ID, "Message", "Vous avez un message",
                Map.of("type", "NEW_MESSAGE"));

        assertThat(result).isFalse();
        verify(userRepository).findById(USER_ID);
    }
}
