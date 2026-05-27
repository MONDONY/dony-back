package com.dony.api.payments;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.payments.cash.CashCommissionService;
import com.dony.api.payments.cash.CommissionStatus;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.payments.wallet.WalletService;
import com.dony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests covering the wallet-based commission deduction path for CASH bids
 * in BidAcceptedEventListener.
 */
@ExtendWith(MockitoExtension.class)
class BidAcceptedCashCommissionTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;
    @Mock private UserRepository userRepository;
    @Mock private BidRepository bidRepository;
    @Mock private WalletService walletService;
    @Mock private CashCommissionService cashCommissionService;
    @Mock private AnnouncementRepository announcementRepository;

    private BidAcceptedEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new BidAcceptedEventListener(
                paymentRepository, auditService, userRepository,
                bidRepository, walletService, cashCommissionService, announcementRepository);
    }

    private BidAcceptedEvent event(UUID bidId, UUID travelerId) {
        return new BidAcceptedEvent(bidId, UUID.randomUUID(), travelerId, UUID.randomUUID());
    }

    private BidEntity cashBidWithWeight(UUID announcementId, BigDecimal weightKg) {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setWeightKg(weightKg);
        bid.setAnnouncementId(announcementId);
        return bid;
    }

    private AnnouncementEntity announcementWithPrice(BigDecimal pricePerKg) {
        AnnouncementEntity ann = new AnnouncementEntity();
        ann.setPricePerKg(pricePerKg);
        return ann;
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void onBidAccepted_cashBidNoCardCharge_debitsCommissionFromWallet() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        BidEntity bid = cashBidWithWeight(announcementId, new BigDecimal("10.00"));
        // commissionStatus is null = not yet charged via card
        AnnouncementEntity announcement = announcementWithPrice(new BigDecimal("10.00"));

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        // cashAmount = 10 * 10 = 100 EUR → commission = 12 EUR (12%)
        // Use any() to avoid BigDecimal scale mismatch (10.00 * 10.00 = 100.0000)
        when(cashCommissionService.computeCommission(any(BigDecimal.class)))
                .thenReturn(new BigDecimal("12.00"));

        listener.onBidAccepted(event(bidId, travelerId));

        verify(walletService).debit(
                eq(travelerId),
                eq(new BigDecimal("12.00")),
                eq(WalletTransactionType.COMMISSION_DEDUCTED),
                eq(bidId)
        );
        // Must NOT touch PaymentRepository (Stripe path)
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void onBidAccepted_cashBidCommissionAlreadyCharged_skipsWalletDebit() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        BidEntity bid = cashBidWithWeight(announcementId, new BigDecimal("5.00"));
        bid.setCommissionStatus(CommissionStatus.CHARGED);

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onBidAccepted(event(bidId, travelerId));

        verify(walletService, never()).debit(any(), any(), any(), any());
        verifyNoInteractions(paymentRepository);
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void onBidAccepted_cashBidAnnouncementMissing_skipsWalletDebit() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        BidEntity bid = cashBidWithWeight(announcementId, new BigDecimal("5.00"));
        // commissionStatus = null (not charged)

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        listener.onBidAccepted(event(bidId, travelerId));

        verify(walletService, never()).debit(any(), any(), any(), any());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void onBidAccepted_cashBidWeightNull_skipsWalletDebit() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.CASH);
        // weightKg is null (not set)
        bid.setAnnouncementId(announcementId);

        AnnouncementEntity announcement = announcementWithPrice(new BigDecimal("10.00"));

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        listener.onBidAccepted(event(bidId, travelerId));

        verify(walletService, never()).debit(any(), any(), any(), any());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void onBidAccepted_cashBidPricePerKgNull_skipsWalletDebit() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        BidEntity bid = cashBidWithWeight(announcementId, new BigDecimal("5.00"));

        AnnouncementEntity announcement = new AnnouncementEntity();
        // pricePerKg is null

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        listener.onBidAccepted(event(bidId, travelerId));

        verify(walletService, never()).debit(any(), any(), any(), any());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void onBidAccepted_stripeBid_doesNotDebitWalletAndProceedsToStripeFlow() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        // BidEntity with default STRIPE paymentMethod
        BidEntity bid = new BidEntity();
        // paymentMethod defaults to STRIPE, so no CASH block runs
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        // No payment found → Stripe path logs warn and returns
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        listener.onBidAccepted(event(bidId, travelerId));

        verify(walletService, never()).debit(any(), any(), any(), any());
        verify(paymentRepository).findByBidId(bidId); // Stripe path was reached
    }

    @Test
    void onBidAccepted_bidNotFound_doesNotDebitWallet() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        // bidForCash == null → CASH block skipped → falls to Stripe path
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        listener.onBidAccepted(event(bidId, travelerId));

        verify(walletService, never()).debit(any(), any(), any(), any());
    }

    @Test
    void onBidAccepted_cashBidMinimumCommissionApplied() {
        // If computeCommission returns minimum amount instead of percentage, it is passed through
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();

        BidEntity bid = cashBidWithWeight(announcementId, new BigDecimal("1.00"));
        AnnouncementEntity announcement = announcementWithPrice(new BigDecimal("5.00"));
        // cashAmount = 1 * 5 = 5 EUR → 12% = 0.60, but minimum is 1.00
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        // Use any() to avoid BigDecimal scale mismatch (1.00 * 5.00 = 5.0000)
        when(cashCommissionService.computeCommission(any(BigDecimal.class)))
                .thenReturn(new BigDecimal("1.00")); // minimum applied

        listener.onBidAccepted(event(bidId, travelerId));

        verify(walletService).debit(
                eq(travelerId),
                eq(new BigDecimal("1.00")),
                eq(WalletTransactionType.COMMISSION_DEDUCTED),
                eq(bidId)
        );
    }
}
