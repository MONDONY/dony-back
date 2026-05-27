package com.dony.api.payments.wallet;

import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletCancellationListenerTest {

    @Mock
    private WalletService walletService;

    @Mock
    private WalletTransactionRepository walletTransactionRepository;

    @Mock
    private BidRepository bidRepository;

    private WalletCancellationListener listener;

    @BeforeEach
    void setUp() {
        listener = new WalletCancellationListener(walletService, walletTransactionRepository, bidRepository);
    }

    // --- Helper builders ---

    private BidEntity cashBid(UUID bidId) {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.CASH);
        return bid;
    }

    private BidEntity stripeBid(UUID bidId) {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        return bid;
    }

    private WalletTransactionEntity commissionTx(BigDecimal amount) {
        WalletTransactionEntity tx = new WalletTransactionEntity();
        // Le débit est stocké en négatif dans WalletService.debit()
        tx.setAmount(amount.negate());
        tx.setType(WalletTransactionType.COMMISSION_DEDUCTED);
        return tx;
    }

    // --- Tests ---

    @Test
    void onTripCancelled_cashBidWithCommission_creditsWallet() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(announcementId, travelerId, List.of(), "reason", List.of(bidId));

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(cashBid(bidId)));
        when(walletTransactionRepository.findByUserIdAndBidIdAndType(travelerId, bidId, WalletTransactionType.COMMISSION_DEDUCTED))
                .thenReturn(Optional.of(commissionTx(new BigDecimal("12.00"))));

        listener.onTripCancelled(event);

        verify(walletService).credit(
                eq(travelerId),
                eq(new BigDecimal("12.00")),
                eq(WalletTransactionType.REFUND),
                eq("cancel-" + bidId),
                eq("wallet-refund-cancel-" + bidId)
        );
    }

    @Test
    void onTripCancelled_stripeBid_doesNotCreditWallet() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(UUID.randomUUID(), travelerId, List.of(), "reason", List.of(bidId));

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(stripeBid(bidId)));

        listener.onTripCancelled(event);

        verify(walletService, never()).credit(any(), any(), any(), any(), any());
        verify(walletTransactionRepository, never()).findByUserIdAndBidIdAndType(any(), any(), any());
    }

    @Test
    void onTripCancelled_cashBidWithoutCommissionInWallet_doesNotCreditWallet() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(UUID.randomUUID(), travelerId, List.of(), "reason", List.of(bidId));

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(cashBid(bidId)));
        when(walletTransactionRepository.findByUserIdAndBidIdAndType(travelerId, bidId, WalletTransactionType.COMMISSION_DEDUCTED))
                .thenReturn(Optional.empty());

        listener.onTripCancelled(event);

        verify(walletService, never()).credit(any(), any(), any(), any(), any());
    }

    @Test
    void onTripCancelled_bidNotFound_doesNotCreditWallet() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(UUID.randomUUID(), travelerId, List.of(), "reason", List.of(bidId));

        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onTripCancelled(event);

        verify(walletService, never()).credit(any(), any(), any(), any(), any());
        verify(walletTransactionRepository, never()).findByUserIdAndBidIdAndType(any(), any(), any());
    }

    @Test
    void onTripCancelled_emptyBidList_doesNothing() {
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(UUID.randomUUID(), travelerId, List.of(), "reason", List.of());

        listener.onTripCancelled(event);

        verify(bidRepository, never()).findById(any());
        verify(walletService, never()).credit(any(), any(), any(), any(), any());
    }

    @Test
    void onTripCancelled_nullBidList_doesNothing() {
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(UUID.randomUUID(), travelerId, List.of(), "reason", null);

        listener.onTripCancelled(event);

        verify(bidRepository, never()).findById(any());
        verify(walletService, never()).credit(any(), any(), any(), any(), any());
    }

    @Test
    void onTripCancelled_multipleBids_processesEachIndependently() {
        UUID bidId1 = UUID.randomUUID();
        UUID bidId2 = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(UUID.randomUUID(), travelerId, List.of(), "reason", List.of(bidId1, bidId2));

        when(bidRepository.findById(bidId1)).thenReturn(Optional.of(cashBid(bidId1)));
        when(bidRepository.findById(bidId2)).thenReturn(Optional.of(cashBid(bidId2)));

        when(walletTransactionRepository.findByUserIdAndBidIdAndType(travelerId, bidId1, WalletTransactionType.COMMISSION_DEDUCTED))
                .thenReturn(Optional.of(commissionTx(new BigDecimal("8.00"))));
        when(walletTransactionRepository.findByUserIdAndBidIdAndType(travelerId, bidId2, WalletTransactionType.COMMISSION_DEDUCTED))
                .thenReturn(Optional.empty()); // bid2 n'a pas de commission wallet

        listener.onTripCancelled(event);

        // bid1 remboursé
        verify(walletService).credit(
                eq(travelerId),
                eq(new BigDecimal("8.00")),
                eq(WalletTransactionType.REFUND),
                eq("cancel-" + bidId1),
                eq("wallet-refund-cancel-" + bidId1)
        );
        // bid2 non remboursé (pas de commission wallet)
        verify(walletService, never()).credit(
                eq(travelerId),
                any(),
                any(),
                eq("cancel-" + bidId2),
                any()
        );
    }

    @Test
    void onTripCancelled_noCommissionTransactionFound_doesNotCreditWallet() {
        // Cas de sécurité : si la transaction COMMISSION_DEDUCTED n'existe pas
        // pour ce couple (travelerId, bidId), le refund n'est pas effectué.
        // Cela prévient les abus si l'event était forgé avec un mauvais travelerId.
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID otherTravelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(UUID.randomUUID(), travelerId, List.of(), "reason", List.of(bidId));

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(cashBid(bidId)));
        // La recherche findByUserIdAndBidIdAndType(travelerId, bidId, ...) retourne empty
        // car la transaction n'existe que pour otherTravelerId
        when(walletTransactionRepository.findByUserIdAndBidIdAndType(travelerId, bidId, WalletTransactionType.COMMISSION_DEDUCTED))
                .thenReturn(Optional.empty());

        listener.onTripCancelled(event);

        verify(walletService, never()).credit(any(), any(), any(), any(), any());
    }
}
