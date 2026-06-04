package com.dony.api.requests.service;

import com.dony.api.common.AuditService;
import com.dony.api.requests.RequestsConfig;
import com.dony.api.requests.entity.NegotiationThreadEntity;
import com.dony.api.requests.entity.NegotiationThreadStatus;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.event.NegotiationExpiredEvent;
import com.dony.api.requests.event.PackageRequestExpiredEvent;
import com.dony.api.requests.repository.NegotiationThreadRepository;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpirationSchedulerTest {

    @Mock private PackageRequestRepository requestRepo;
    @Mock private NegotiationThreadRepository threadRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;

    @InjectMocks private ExpirationScheduler scheduler;

    private static void setId(Object entity, UUID id) {
        try {
            var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("requests passé desired_date → EXPIRED + event + audit")
    void expireRequests_marksAndPublishes() {
        PackageRequestEntity pastReq = new PackageRequestEntity();
        pastReq.setSenderId(UUID.randomUUID());
        pastReq.setStatus(PackageRequestStatus.OPEN);
        setId(pastReq, UUID.randomUUID());

        when(requestRepo.findExpired(any(LocalDate.class))).thenReturn(List.of(pastReq));

        scheduler.expireRequests();

        assertThat(pastReq.getStatus()).isEqualTo(PackageRequestStatus.EXPIRED);
        verify(requestRepo).save(pastReq);
        verify(eventPublisher).publishEvent(any(PackageRequestExpiredEvent.class));
        verify(auditService).log(eq("PACKAGE_REQUEST"), any(UUID.class), eq("EXPIRED"),
            isNull(), anyMap());
    }

    private NegotiationThreadEntity thread(NegotiationThreadStatus status) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(UUID.randomUUID());
        t.setTravelerId(UUID.randomUUID());
        t.setStatus(status);
        setId(t, UUID.randomUUID());
        return t;
    }

    @Test
    @DisplayName("threads inactifs → EXPIRED + event + audit")
    void expireThreads_marksAndPublishes() {
        when(config.threadInactivityHours()).thenReturn(48);
        when(config.awaitingTripHours()).thenReturn(24);
        when(config.awaitingPaymentHours()).thenReturn(24);

        NegotiationThreadEntity openThread = thread(NegotiationThreadStatus.OPEN);

        when(threadRepo.findInactive(any(LocalDateTime.class))).thenReturn(List.of(openThread));
        when(threadRepo.findAwaitingTripExpired(any(LocalDateTime.class))).thenReturn(List.of());
        when(threadRepo.findAwaitingPaymentExpired(any(LocalDateTime.class))).thenReturn(List.of());

        scheduler.expireThreads();

        assertThat(openThread.getStatus()).isEqualTo(NegotiationThreadStatus.EXPIRED);
        verify(threadRepo).save(openThread);
        verify(eventPublisher).publishEvent(any(NegotiationExpiredEvent.class));
        verify(auditService).log(eq("NEGOTIATION_THREAD"), any(UUID.class), eq("EXPIRED"),
            isNull(), anyMap());
    }

    @Test
    @DisplayName("AWAITING_TRIP et AWAITING_PAYMENT dépassés → EXPIRED + events + audits")
    void expireThreads_marksAwaitingTripAndPaymentExpired() {
        when(config.threadInactivityHours()).thenReturn(72);
        when(config.awaitingTripHours()).thenReturn(24);
        when(config.awaitingPaymentHours()).thenReturn(24);
        when(threadRepo.findInactive(any())).thenReturn(List.of());
        NegotiationThreadEntity t1 = thread(NegotiationThreadStatus.AWAITING_TRIP);
        NegotiationThreadEntity t2 = thread(NegotiationThreadStatus.AWAITING_PAYMENT);
        when(threadRepo.findAwaitingTripExpired(any())).thenReturn(List.of(t1));
        when(threadRepo.findAwaitingPaymentExpired(any())).thenReturn(List.of(t2));

        scheduler.expireThreads();

        assertThat(t1.getStatus()).isEqualTo(NegotiationThreadStatus.EXPIRED);
        assertThat(t2.getStatus()).isEqualTo(NegotiationThreadStatus.EXPIRED);
        verify(eventPublisher, times(2)).publishEvent(any(NegotiationExpiredEvent.class));
    }

    @Test
    @DisplayName("runExpiration() appelle expireRequests() et expireThreads() en une transaction")
    void runExpiration_delegatesToBothMethods() {
        when(config.threadInactivityHours()).thenReturn(72);
        when(config.awaitingTripHours()).thenReturn(24);
        when(config.awaitingPaymentHours()).thenReturn(24);
        when(requestRepo.findExpired(any())).thenReturn(List.of());
        when(threadRepo.findInactive(any())).thenReturn(List.of());
        when(threadRepo.findAwaitingTripExpired(any())).thenReturn(List.of());
        when(threadRepo.findAwaitingPaymentExpired(any())).thenReturn(List.of());

        scheduler.runExpiration();

        // Both repos were queried → both sub-methods ran
        verify(requestRepo).findExpired(any());
        verify(threadRepo).findInactive(any());
    }
}
