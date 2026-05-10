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

    @Test
    @DisplayName("threads inactifs → EXPIRED + event + audit")
    void expireThreads_marksAndPublishes() {
        when(config.threadInactivityHours()).thenReturn(48);

        NegotiationThreadEntity thread = new NegotiationThreadEntity();
        thread.setPackageRequestId(UUID.randomUUID());
        thread.setTravelerId(UUID.randomUUID());
        thread.setStatus(NegotiationThreadStatus.OPEN);
        setId(thread, UUID.randomUUID());

        when(threadRepo.findInactive(any(LocalDateTime.class))).thenReturn(List.of(thread));

        scheduler.expireThreads();

        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.EXPIRED);
        verify(threadRepo).save(thread);
        verify(eventPublisher).publishEvent(any(NegotiationExpiredEvent.class));
        verify(auditService).log(eq("NEGOTIATION_THREAD"), any(UUID.class), eq("EXPIRED"),
            isNull(), anyMap());
    }
}
