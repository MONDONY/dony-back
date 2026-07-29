package com.yadony.api.payments.mobilemoney;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MobileMoneyBidAcceptedListenerTest {

    @Mock private MobileMoneyPaymentService mmPaymentService;
    @Mock private BidRepository bidRepository;
    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private MobileMoneyBidAcceptedListener listener;

    private final UUID bidId    = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    // ── bid not found ────────────────────────────────────────────────────────

    @Test
    void onBidAccepted_bidNotFound_logsAndReturns() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        BidAcceptedEvent event = new BidAcceptedEvent(bidId, senderId, UUID.randomUUID(), UUID.randomUUID());
        listener.onBidAccepted(event);

        verify(mmPaymentService, never()).initiate(any(), any());
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    // ── non-MM provider ──────────────────────────────────────────────────────

    @Test
    void onBidAccepted_cashPaymentMethod_skipsInitiation() {
        BidEntity bid = bidWithMethod(PaymentMethod.CASH);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        BidAcceptedEvent event = new BidAcceptedEvent(bidId, senderId, UUID.randomUUID(), UUID.randomUUID());
        listener.onBidAccepted(event);

        verify(mmPaymentService, never()).initiate(any(), any());
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    // ── WAVE ─────────────────────────────────────────────────────────────────

    @Test
    void onBidAccepted_wavePaymentMethod_initiatesAndNotifies() {
        BidEntity bid = bidWithMethod(PaymentMethod.WAVE);
        bid.setSenderId(senderId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        MobileMoneyPaymentEntity payment = new MobileMoneyPaymentEntity();
        payment.setPaymentLink("https://wave.test/pay?ref=wave_ref_1");
        when(mmPaymentService.initiate(eq(bidId), eq(senderId))).thenReturn(payment);

        BidAcceptedEvent event = new BidAcceptedEvent(bidId, senderId, UUID.randomUUID(), UUID.randomUUID());
        listener.onBidAccepted(event);

        verify(mmPaymentService).initiate(eq(bidId), eq(senderId));
        verify(notificationDispatcher).notifyUser(
                eq(senderId), anyString(), anyString(), any());
    }

    // ── ORANGE_MONEY ──────────────────────────────────────────────────────────

    @Test
    void onBidAccepted_orangeMoneyPaymentMethod_initiatesAndNotifies() {
        BidEntity bid = bidWithMethod(PaymentMethod.ORANGE_MONEY);
        bid.setSenderId(senderId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        MobileMoneyPaymentEntity payment = new MobileMoneyPaymentEntity();
        payment.setPaymentLink(null); // link may be null if gateway stub did not return one
        when(mmPaymentService.initiate(eq(bidId), eq(senderId))).thenReturn(payment);

        BidAcceptedEvent event = new BidAcceptedEvent(bidId, senderId, UUID.randomUUID(), UUID.randomUUID());
        listener.onBidAccepted(event);

        verify(mmPaymentService).initiate(eq(bidId), eq(senderId));
        verify(notificationDispatcher).notifyUser(
                eq(senderId), anyString(), anyString(), any());
    }

    // ── exception during initiation ──────────────────────────────────────────

    /**
     * Le push générique « Demande acceptée ! » est supprimé en amont pour les bids Mobile
     * Money, parce que la notification de paiement est censée le remplacer. Si l'initiation
     * échoue, ce repli est la SEULE notification que l'expéditeur recevra : sans lui, son
     * colis serait accepté sans qu'il en soit jamais averti.
     */
    @Test
    void onBidAccepted_mmServiceThrows_fallsBackToAcceptanceNotification() {
        BidEntity bid = bidWithMethod(PaymentMethod.WAVE);
        bid.setSenderId(senderId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(mmPaymentService.initiate(any(), any())).thenThrow(new RuntimeException("Gateway error"));

        BidAcceptedEvent event = new BidAcceptedEvent(bidId, senderId, UUID.randomUUID(), UUID.randomUUID(), true);

        // Should NOT propagate exception — listener catches and logs
        listener.onBidAccepted(event);

        verify(notificationDispatcher).notifyUser(
                eq(senderId), eq("Demande acceptée !"), anyString(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private BidEntity bidWithMethod(PaymentMethod method) {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(method);
        return bid;
    }
}
