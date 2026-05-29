package com.dony.api.payments.wallet;

import com.dony.api.cancellation.events.TripCancelledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

    private WalletCancellationListener listener;

    @BeforeEach
    void setUp() {
        listener = new WalletCancellationListener(walletService, walletTransactionRepository);
    }

    // --- Helper builders ---

    private WalletTransactionEntity commissionTx(BigDecimal amount) {
        WalletTransactionEntity tx = new WalletTransactionEntity();
        // Le débit est stocké en négatif dans WalletService.debit()
        tx.setAmount(amount.negate());
        tx.setType(WalletTransactionType.COMMISSION_DEDUCTED);
        return tx;
    }

    private TripCancelledEvent cashEvent(UUID announcementId, UUID travelerId, UUID bidId) {
        return new TripCancelledEvent(
                announcementId, travelerId, List.of(), "reason",
                List.of(bidId), Map.of(bidId, "CASH"));
    }

    private TripCancelledEvent stripeEvent(UUID announcementId, UUID travelerId, UUID bidId) {
        return new TripCancelledEvent(
                announcementId, travelerId, List.of(), "reason",
                List.of(bidId), Map.of(bidId, "STRIPE"));
    }

    // --- Tests ---

    @Test
    void onTripCancelled_cashBidWithCommission_creditsWallet() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        TripCancelledEvent event = cashEvent(announcementId, travelerId, bidId);

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

        TripCancelledEvent event = stripeEvent(UUID.randomUUID(), travelerId, bidId);

        listener.onTripCancelled(event);

        verify(walletService, never()).credit(any(), any(), any(), any(), any());
        verify(walletTransactionRepository, never()).findByUserIdAndBidIdAndType(any(), any(), any());
    }

    @Test
    void onTripCancelled_cashBidWithoutCommissionInWallet_doesNotCreditWallet() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = cashEvent(UUID.randomUUID(), travelerId, bidId);

        when(walletTransactionRepository.findByUserIdAndBidIdAndType(travelerId, bidId, WalletTransactionType.COMMISSION_DEDUCTED))
                .thenReturn(Optional.empty());

        listener.onTripCancelled(event);

        verify(walletService, never()).credit(any(), any(), any(), any(), any());
    }

    @Test
    void onTripCancelled_bidNotInPaymentMethodMap_defaultsToStripe_doesNotCreditWallet() {
        // Bid présent dans affectedBidIds mais absent de bidPaymentMethods → défaut STRIPE → skip
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(
                UUID.randomUUID(), travelerId, List.of(), "reason",
                List.of(bidId), Map.of() /* bidPaymentMethods vide */);

        listener.onTripCancelled(event);

        verify(walletService, never()).credit(any(), any(), any(), any(), any());
        verify(walletTransactionRepository, never()).findByUserIdAndBidIdAndType(any(), any(), any());
    }

    @Test
    void onTripCancelled_emptyBidList_doesNothing() {
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(
                UUID.randomUUID(), travelerId, List.of(), "reason",
                List.of(), Map.of());

        listener.onTripCancelled(event);

        verify(walletTransactionRepository, never()).findByUserIdAndBidIdAndType(any(), any(), any());
        verify(walletService, never()).credit(any(), any(), any(), any(), any());
    }

    @Test
    void onTripCancelled_nullBidList_doesNothing() {
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(
                UUID.randomUUID(), travelerId, List.of(), "reason",
                null, Map.of());

        listener.onTripCancelled(event);

        verify(walletTransactionRepository, never()).findByUserIdAndBidIdAndType(any(), any(), any());
        verify(walletService, never()).credit(any(), any(), any(), any(), any());
    }

    @Test
    void onTripCancelled_multipleBids_processesEachIndependently() {
        UUID bidId1 = UUID.randomUUID();
        UUID bidId2 = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        TripCancelledEvent event = new TripCancelledEvent(
                UUID.randomUUID(), travelerId, List.of(), "reason",
                List.of(bidId1, bidId2),
                Map.of(bidId1, "CASH", bidId2, "CASH"));

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

        TripCancelledEvent event = cashEvent(UUID.randomUUID(), travelerId, bidId);

        // La recherche findByUserIdAndBidIdAndType(travelerId, bidId, ...) retourne empty
        when(walletTransactionRepository.findByUserIdAndBidIdAndType(travelerId, bidId, WalletTransactionType.COMMISSION_DEDUCTED))
                .thenReturn(Optional.empty());

        listener.onTripCancelled(event);

        verify(walletService, never()).credit(any(), any(), any(), any(), any());
    }
}
