package com.dony.api.payments.cash.job;

import com.dony.api.cancellation.CancellationEntity;
import com.dony.api.cancellation.CancellationReason;
import com.dony.api.cancellation.CancellationRepository;
import com.dony.api.cancellation.CancellationStatus;
import com.dony.api.cancellation.events.CancellationConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoShowContestationTimeoutJobTest {

    @Mock private CancellationRepository cancellationRepo;
    @Mock private ApplicationEventPublisher events;

    private NoShowContestationTimeoutJob job;

    @BeforeEach
    void setUp() {
        job = new NoShowContestationTimeoutJob(cancellationRepo, events);
    }

    private CancellationEntity pending() {
        CancellationEntity c = new CancellationEntity();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setBidId(UUID.randomUUID());
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        c.setReason(CancellationReason.SENDER_NO_SHOW.name());
        c.setContestationDeadline(OffsetDateTime.now().minusMinutes(5));
        return c;
    }

    @Test
    void confirmsExpiredPendingAndPublishesEvent() {
        CancellationEntity c = pending();
        when(cancellationRepo.findExpiredPending(any())).thenReturn(List.of(c));
        when(cancellationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        job.run();

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
        verify(cancellationRepo).save(c);

        ArgumentCaptor<CancellationConfirmedEvent> captor =
                ArgumentCaptor.forClass(CancellationConfirmedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo(CancellationReason.SENDER_NO_SHOW);
        assertThat(captor.getValue().bidId()).isEqualTo(c.getBidId());
    }

    @Test
    void noExpiredPendingIsNoOp() {
        when(cancellationRepo.findExpiredPending(any())).thenReturn(List.of());

        job.run();

        verifyNoInteractions(events);
        verify(cancellationRepo, never()).save(any());
    }
}
