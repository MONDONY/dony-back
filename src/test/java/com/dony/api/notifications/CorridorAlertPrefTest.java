package com.dony.api.notifications;

import com.dony.api.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorridorAlertPrefTest {

    @Mock NotificationPrefsJpaRepository repository;
    @Mock UserRepository userRepository;
    @InjectMocks NotificationPrefsService service;

    @Test
    void defaults_pushCorridorAlerts_isTrue() {
        assertThat(NotificationPrefsDto.defaults().pushCorridorAlerts()).isTrue();
    }

    @Test
    void isAllowed_corridorAlert_respectsToggleOff() {
        UUID userId = UUID.randomUUID();
        NotificationPrefsEntity prefs = new NotificationPrefsEntity();
        prefs.setUserId(userId);
        prefs.setPushCorridorAlerts(false);
        when(repository.findById(userId)).thenReturn(Optional.of(prefs));

        assertThat(service.isAllowed(userId, "CORRIDOR_ALERT")).isFalse();
    }

    @Test
    void isAllowed_corridorAlert_defaultsAllowedWhenNoPrefsRow() {
        UUID userId = UUID.randomUUID();
        when(repository.findById(userId)).thenReturn(Optional.empty());

        assertThat(service.isAllowed(userId, "CORRIDOR_ALERT")).isTrue();
    }

    @Test
    void entity_pushCorridorAlerts_defaultsTrue() {
        assertThat(new NotificationPrefsEntity().isPushCorridorAlerts()).isTrue();
    }
}
