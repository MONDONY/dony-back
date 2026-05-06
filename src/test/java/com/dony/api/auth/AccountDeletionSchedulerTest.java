package com.dony.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDeletionScheduler — tests unitaires")
class AccountDeletionSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private UserService userService;

    @InjectMocks private AccountDeletionScheduler scheduler;

    private UserEntity makeUser(UUID id, Instant deletionRequestedAt) {
        UserEntity u = new UserEntity();
        setId(u, id);
        u.setFirebaseUid("uid-" + id);
        u.setStatus(UserStatus.PENDING_DELETION);
        u.setDeletionRequestedAt(deletionRequestedAt);
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("finalise uniquement les users avec deletionRequestedAt > 30j")
    void finalizesExpiredUsersOnly() {
        UUID expiredId = UUID.randomUUID();
        Instant expired = Instant.now().minus(31, ChronoUnit.DAYS);
        UserEntity expiredUser = makeUser(expiredId, expired);

        when(userRepository.findByStatusAndDeletionRequestedAtBefore(
                eq(UserStatus.PENDING_DELETION), any(Instant.class)))
                .thenReturn(List.of(expiredUser));

        scheduler.finalizeExpiredDeletions();

        verify(userService).finalizeGdprDeletion(expiredUser);
    }

    @Test
    @DisplayName("aucun user expiré → finalizeGdprDeletion jamais appelé")
    void noExpiredUsers_doesNothing() {
        when(userRepository.findByStatusAndDeletionRequestedAtBefore(
                eq(UserStatus.PENDING_DELETION), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.finalizeExpiredDeletions();

        verify(userService, never()).finalizeGdprDeletion(any());
    }

    @Test
    @DisplayName("cutoff passé au repository est bien now - 30 jours")
    void cutoffIs30DaysAgo() {
        when(userRepository.findByStatusAndDeletionRequestedAtBefore(
                eq(UserStatus.PENDING_DELETION), any(Instant.class)))
                .thenReturn(List.of());

        Instant before = Instant.now().minus(30, ChronoUnit.DAYS);
        scheduler.finalizeExpiredDeletions();
        Instant after = Instant.now().minus(30, ChronoUnit.DAYS);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(userRepository).findByStatusAndDeletionRequestedAtBefore(
                eq(UserStatus.PENDING_DELETION), captor.capture());

        Instant cutoff = captor.getValue();
        assertThat(cutoff).isBetween(before.minusSeconds(5), after.plusSeconds(5));
    }
}
