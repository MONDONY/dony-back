package com.dony.api.alerts;

import com.dony.api.notifications.NotificationDispatcher;
import com.dony.api.requests.entity.PackageRequestEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CorridorAlertDigestSchedulerTest {

    @Mock CorridorAlertRepository alertRepository;
    @Mock AlertService alertService;
    @Mock NotificationDispatcher notificationDispatcher;

    CorridorAlertDigestScheduler scheduler;

    final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        scheduler = new CorridorAlertDigestScheduler(alertRepository, alertService, notificationDispatcher);
    }

    private static void setId(Object target, UUID id) {
        try {
            var f = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private CorridorAlertEntity alert(LocalDateTime lastNotifiedAt) {
        CorridorAlertEntity a = new CorridorAlertEntity();
        setId(a, UUID.randomUUID());
        a.setOwnerId(ownerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setActive(true);
        a.setLastNotifiedAt(lastNotifiedAt);
        return a;
    }

    @Test
    void dispatchesAndBumpsLastNotified_whenMatchesExist() {
        CorridorAlertEntity a = alert(null);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(alertService.findRecentMatches(eq(a), any()))
                .thenReturn(List.of(new PackageRequestEntity(), new PackageRequestEntity()));

        scheduler.runDigest();

        ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), anyString(), anyString(), dataCaptor.capture());
        assertThat(dataCaptor.getValue().get("type")).isEqualTo("CORRIDOR_ALERT");
        assertThat(a.getLastNotifiedAt()).isNotNull();
        verify(alertRepository).save(a);
    }

    @Test
    void skipsAlert_whenNoMatches() {
        CorridorAlertEntity a = alert(LocalDateTime.now().minusDays(1));
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(alertService.findRecentMatches(eq(a), any())).thenReturn(List.of());

        scheduler.runDigest();

        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), anyMap());
        verify(alertRepository, never()).save(any());
    }

    @Test
    void usesLastNotifiedAt_asSinceCutoff() {
        LocalDateTime last = LocalDateTime.of(2026, 6, 1, 9, 0);
        CorridorAlertEntity a = alert(last);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(alertService.findRecentMatches(eq(a), eq(last))).thenReturn(List.of(new PackageRequestEntity()));

        scheduler.runDigest();

        verify(alertService).findRecentMatches(a, last);
    }
}
