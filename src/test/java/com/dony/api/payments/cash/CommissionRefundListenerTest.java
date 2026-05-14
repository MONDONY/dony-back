package com.dony.api.payments.cash;

import com.dony.api.cancellation.CancellationReason;
import com.dony.api.cancellation.events.CancellationConfirmedEvent;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommissionRefundListenerTest {

    @Mock private CashCommissionService cashCommissionService;
    @Mock private BidRepository bidRepository;

    private CommissionRefundListener listener;

    private final UUID bidId    = UUID.randomUUID();
    private final UUID cancelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new CommissionRefundListener(cashCommissionService, bidRepository);
    }

    @Test
    void refundsCommissionForCashBidWithChargedStatus() {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCommissionStatus(CommissionStatus.CHARGED);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(bidId, cancelId, CancellationReason.SENDER_NO_SHOW));

        verify(cashCommissionService).refundCommission(bid);
    }

    @Test
    void ignoresNonSenderNoShowReason() {
        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(bidId, cancelId, CancellationReason.TRIP_CANCELLED));

        verifyNoInteractions(cashCommissionService, bidRepository);
    }

    @Test
    void ignoresNonCashBid() {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setCommissionStatus(CommissionStatus.CHARGED);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(bidId, cancelId, CancellationReason.SENDER_NO_SHOW));

        verify(cashCommissionService, never()).refundCommission(any());
    }

    @Test
    void ignoresBidWithUnchargedCommission() {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCommissionStatus(CommissionStatus.FAILED);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(bidId, cancelId, CancellationReason.SENDER_NO_SHOW));

        verify(cashCommissionService, never()).refundCommission(any());
    }
}
