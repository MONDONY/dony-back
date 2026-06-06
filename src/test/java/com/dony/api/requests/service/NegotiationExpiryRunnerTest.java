package com.dony.api.requests.service;

import com.dony.api.common.AuditService;
import com.dony.api.requests.entity.NegotiationThreadEntity;
import com.dony.api.requests.entity.NegotiationThreadStatus;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.event.NegotiationExpiredEvent;
import com.dony.api.requests.event.PackageRequestExpiredEvent;
import com.dony.api.requests.repository.NegotiationThreadRepository;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NegotiationExpiryRunnerTest {

    @Mock private PackageRequestRepository requestRepo;
    @Mock private NegotiationThreadRepository threadRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @InjectMocks private NegotiationExpiryRunner runner;

    private static void setId(Object entity, UUID id) {
        try {
            var f = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private NegotiationThreadEntity thread(NegotiationThreadStatus status) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(UUID.randomUUID());
        t.setTravelerId(UUID.randomUUID());
        t.setStatus(status);
        setId(t, UUID.randomUUID());
        return t;
    }

    private PackageRequestEntity request(PackageRequestStatus status) {
        PackageRequestEntity r = new PackageRequestEntity();
        r.setSenderId(UUID.randomUUID());
        r.setStatus(status);
        setId(r, UUID.randomUUID());
        return r;
    }

    @Test
    @DisplayName("expireThread — statut toujours attendu → EXPIRED + event + audit")
    void expireThread_statusMatches_expires() {
        NegotiationThreadEntity t = thread(NegotiationThreadStatus.AWAITING_PAYMENT);
        when(threadRepo.findById(t.getId())).thenReturn(Optional.of(t));

        runner.expireThread(t.getId(), NegotiationThreadStatus.AWAITING_PAYMENT, "AWAITING_PAYMENT_TIMEOUT");

        assertThat(t.getStatus()).isEqualTo(NegotiationThreadStatus.EXPIRED);
        verify(threadRepo).save(t);
        verify(eventPublisher).publishEvent(any(NegotiationExpiredEvent.class));
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(t.getId()), eq("EXPIRED"), isNull(), anyMap());
    }

    @Test
    @DisplayName("expireThread — statut changé en concurrence (ACCEPTED) → skip idempotent, pas de clobber")
    void expireThread_statusChangedConcurrently_skips() {
        NegotiationThreadEntity t = thread(NegotiationThreadStatus.ACCEPTED);
        when(threadRepo.findById(t.getId())).thenReturn(Optional.of(t));

        runner.expireThread(t.getId(), NegotiationThreadStatus.AWAITING_PAYMENT, "AWAITING_PAYMENT_TIMEOUT");

        assertThat(t.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED); // inchangé
        verify(threadRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("expireThread — thread introuvable → no-op")
    void expireThread_notFound_noop() {
        UUID id = UUID.randomUUID();
        when(threadRepo.findById(id)).thenReturn(Optional.empty());

        runner.expireThread(id, NegotiationThreadStatus.OPEN, "INACTIVE_OPEN");

        verify(threadRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("expireRequest — encore OPEN → EXPIRED + event + audit")
    void expireRequest_stillOpen_expires() {
        PackageRequestEntity r = request(PackageRequestStatus.OPEN);
        when(requestRepo.findById(r.getId())).thenReturn(Optional.of(r));

        runner.expireRequest(r.getId());

        assertThat(r.getStatus()).isEqualTo(PackageRequestStatus.EXPIRED);
        verify(requestRepo).save(r);
        verify(eventPublisher).publishEvent(any(PackageRequestExpiredEvent.class));
        verify(auditService).log(eq("PACKAGE_REQUEST"), eq(r.getId()), eq("EXPIRED"), isNull(), anyMap());
    }

    @Test
    @DisplayName("expireRequest — déjà ACCEPTED → skip idempotent")
    void expireRequest_alreadyAccepted_skips() {
        PackageRequestEntity r = request(PackageRequestStatus.ACCEPTED);
        when(requestRepo.findById(r.getId())).thenReturn(Optional.of(r));

        runner.expireRequest(r.getId());

        assertThat(r.getStatus()).isEqualTo(PackageRequestStatus.ACCEPTED);
        verify(requestRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }
}
