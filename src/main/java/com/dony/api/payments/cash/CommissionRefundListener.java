package com.dony.api.payments.cash;

import com.dony.api.cancellation.CancellationReason;
import com.dony.api.cancellation.events.CancellationConfirmedEvent;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Rembourse la commission Dony sur SENDER_NO_SHOW.
 * Route vers le bon canal selon commissionChargedVia :
 * - WALLET → credit wallet (clé idempotente "wallet-refund-noshow-{bidId}")
 * - CARD   → refund Stripe (clé idempotente "bid_refund_{bidId}")
 * - null   → log d'anomalie, pas de remboursement
 */
@Component
public class CommissionRefundListener {

    private static final Logger log = LoggerFactory.getLogger(CommissionRefundListener.class);

    private final CashCommissionService cashCommissionService;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;

    public CommissionRefundListener(CashCommissionService cashCommissionService,
                                    BidRepository bidRepository,
                                    AnnouncementRepository announcementRepository) {
        this.cashCommissionService = cashCommissionService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        if (event.reason() != CancellationReason.SENDER_NO_SHOW) return;

        BidEntity bid = bidRepository.findById(event.bidId()).orElse(null);
        if (bid == null) return;
        if (bid.getPaymentMethod() != PaymentMethod.CASH) return;
        if (bid.getCommissionStatus() != CommissionStatus.CHARGED) return;

        CommissionChargedVia via = bid.getCommissionChargedVia();
        if (via == null) {
            log.warn("CommissionRefundListener: bid {} CHARGED mais commissionChargedVia=null — remboursement impossible", bid.getId());
            return;
        }

        switch (via) {
            case WALLET -> {
                AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
                if (announcement == null) {
                    log.warn("CommissionRefundListener: annonce introuvable pour bid {} — wallet refund impossible", bid.getId());
                    return;
                }
                cashCommissionService.refundCommissionToWallet(
                        bid, announcement.getTravelerId(), "wallet-refund-noshow-" + bid.getId());
            }
            case CARD -> cashCommissionService.refundCommission(bid);
        }
    }
}
