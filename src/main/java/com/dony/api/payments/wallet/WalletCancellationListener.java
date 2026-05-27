package com.dony.api.payments.wallet;

import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 7 — Remboursement commission wallet sur annulation de voyage.
 *
 * Lorsqu'un voyageur annule un trajet, pour chaque bid CASH dont la commission
 * a été prélevée depuis le wallet (COMMISSION_DEDUCTED), on rembourse ce montant
 * (REFUND) dans le wallet du voyageur.
 *
 * Règles :
 * - Seulement les bids avec paymentMethod = CASH
 * - Seulement si une transaction COMMISSION_DEDUCTED existe pour ce bid
 * - Idempotence garantie par la clé "wallet-refund-cancel-{bidId}" passée à WalletService.credit()
 * - @TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW) obligatoire
 */
@Component
public class WalletCancellationListener {

    private static final Logger log = LoggerFactory.getLogger(WalletCancellationListener.class);

    private final WalletService walletService;
    private final WalletTransactionRepository walletTransactionRepository;
    private final BidRepository bidRepository;

    public WalletCancellationListener(WalletService walletService,
                                      WalletTransactionRepository walletTransactionRepository,
                                      BidRepository bidRepository) {
        this.walletService = walletService;
        this.walletTransactionRepository = walletTransactionRepository;
        this.bidRepository = bidRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTripCancelled(TripCancelledEvent event) {
        List<UUID> bidIds = event.getAffectedBidIds();
        if (bidIds == null || bidIds.isEmpty()) {
            return;
        }

        UUID travelerId = event.getTravelerId();
        log.info("WalletCancellationListener: TripCancelledEvent for announcement={}, travelerId={}, {} bid(s)",
                event.getAnnouncementId(), travelerId, bidIds.size());

        for (UUID bidId : bidIds) {
            processWalletRefundForBid(bidId, travelerId);
        }
    }

    private void processWalletRefundForBid(UUID bidId, UUID travelerId) {
        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null) {
            log.warn("WalletCancellationListener: bid {} not found, skipping wallet refund", bidId);
            return;
        }

        if (bid.getPaymentMethod() != PaymentMethod.CASH) {
            log.debug("WalletCancellationListener: bid {} paymentMethod={} — not CASH, skipping",
                    bidId, bid.getPaymentMethod());
            return;
        }

        // Vérification de sécurité : la recherche de transaction COMMISSION_DEDUCTED
        // via findByUserIdAndBidIdAndType validera implicitement l'ownership du travelerId.
        // Si aucune transaction n'existe pour ce couple (travelerId, bidId), on skip.
        // Cela prévient les refunds non autorisés.

        // Retrouver la transaction COMMISSION_DEDUCTED pour ce bid
        Optional<WalletTransactionEntity> commissionTx =
                walletTransactionRepository.findByUserIdAndBidIdAndType(
                        travelerId, bidId, WalletTransactionType.COMMISSION_DEDUCTED);

        if (commissionTx.isEmpty()) {
            log.info("WalletCancellationListener: aucune commission wallet trouvée pour bid {} traveler {} — pas de remboursement",
                    bidId, travelerId);
            return;
        }

        // Le montant stocké dans COMMISSION_DEDUCTED est négatif (debit), on prend la valeur absolue
        WalletTransactionEntity tx = commissionTx.get();
        java.math.BigDecimal refundAmount = tx.getAmount().abs();

        String idempotencyKey = "wallet-refund-cancel-" + bidId;
        String paymentRef = "cancel-" + bidId;

        walletService.credit(
                travelerId,
                refundAmount,
                WalletTransactionType.REFUND,
                paymentRef,
                idempotencyKey
        );

        log.info("WalletCancellationListener: remboursement {} EUR crédité dans le wallet du voyageur {} pour bid {}",
                refundAmount, travelerId, bidId);
    }
}
