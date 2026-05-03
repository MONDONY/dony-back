package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.BidRejectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidTimeoutSchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private BidTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BidTimeoutScheduler(bidRepository, auditService, eventPublisher);
    }

    private BidEntity timedOutBid() {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setStatus(BidStatus.PENDING);
        b.setSenderId(UUID.randomUUID());
        return b;
    }

    @Test
    void cancels_timed_out_bids_and_publishes_event() {
        BidEntity bid = timedOutBid();
        when(bidRepository.findPendingTimedOut(any(), any())).thenReturn(List.of(bid));

        scheduler.autoCancelUnansweredBids();

        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bid.getRejectionReason()).isEqualTo("TRAVELER_NO_RESPONSE");

        verify(bidRepository).save(bid);
        verify(auditService).log(eq("BID"), eq(bid.getId()), eq("BID_AUTO_CANCELLED_TIMEOUT"), any(), any());

        ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getBidId()).isEqualTo(bid.getId());
        assertThat(captor.getValue().getReason()).isEqualTo("TRAVELER_NO_RESPONSE");
    }

    @Test
    void handles_multiple_bids() {
        BidEntity b1 = timedOutBid();
        BidEntity b2 = timedOutBid();
        when(bidRepository.findPendingTimedOut(any(), any())).thenReturn(List.of(b1, b2));

        scheduler.autoCancelUnansweredBids();

        verify(bidRepository).save(b1);
        verify(bidRepository).save(b2);
        verify(eventPublisher, times(2)).publishEvent(any(BidRejectedEvent.class));
    }

    @Test
    void no_op_when_nothing_timed_out() {
        when(bidRepository.findPendingTimedOut(any(), any())).thenReturn(List.of());

        scheduler.autoCancelUnansweredBids();

        verifyNoInteractions(auditService);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
