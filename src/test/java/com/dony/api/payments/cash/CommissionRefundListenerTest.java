package com.dony.api.payments.cash;

import com.dony.api.cancellation.CancellationReason;
import com.dony.api.cancellation.events.CancellationConfirmedEvent;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommissionRefundListenerTest {

    @Mock private CashCommissionService cashCommissionService;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;

    private CommissionRefundListener listener;

    private final UUID bidId    = UUID.randomUUID();
    private final UUID cancelId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new CommissionRefundListener(cashCommissionService, bidRepository, announcementRepository);
    }

    @Test
    void routesToStripeRefundWhenChargedViaCard() {
        BidEntity bid = cashBid(CommissionChargedVia.CARD);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        // CARD path: pas besoin de l'annonce (getTravelerId vient du bid directement via annonce que le CARD path n'utilise pas)

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(bidId, cancelId, CancellationReason.SENDER_NO_SHOW));

        verify(cashCommissionService).refundCommission(bid);
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void routesToWalletRefundWhenChargedViaWallet() {
        BidEntity bid = cashBid(CommissionChargedVia.WALLET);
        AnnouncementEntity ann = announcement();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(bidId, cancelId, CancellationReason.SENDER_NO_SHOW));

        verify(cashCommissionService).refundCommissionToWallet(eq(bid), eq(travelerId),
                eq("wallet-refund-noshow-" + bidId));
        verify(cashCommissionService, never()).refundCommission(any());
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
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
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
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    @Test
    void ignoresBidWithNullChargedVia() {
        BidEntity bid = cashBid(null);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(bidId, cancelId, CancellationReason.SENDER_NO_SHOW));

        verify(cashCommissionService, never()).refundCommission(any());
        verify(cashCommissionService, never()).refundCommissionToWallet(any(), any(), any());
    }

    // --- helpers ---

    private BidEntity cashBid(CommissionChargedVia via) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCommissionStatus(CommissionStatus.CHARGED);
        bid.setCommissionChargedVia(via);
        bid.setAnnouncementId(announcementId);
        return bid;
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", announcementId);
        ann.setTravelerId(travelerId);
        return ann;
    }
}
