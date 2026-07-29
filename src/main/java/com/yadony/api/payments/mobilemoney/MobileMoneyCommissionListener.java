package com.yadony.api.payments.mobilemoney;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.CashCommissionService;
import com.yadony.api.payments.mobilemoney.events.BidPaidByMobileMoneyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Prélève la commission Yadony (taux configuré via {@code yadony.commission.rate}) après confirmation du paiement Mobile Money.
 *
 * Logique : wallet prioritaire → carte en fallback automatique (chargeCommissionAuto).
 * Si ni wallet ni carte → commission FAILED (créance), le trajet n'est PAS annulé
 * (le paiement du principal a déjà eu lieu).
 */
@Component
public class MobileMoneyCommissionListener {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyCommissionListener.class);

    private final CashCommissionService commissionService;
    private final BidRepository bidRepository;

    public MobileMoneyCommissionListener(CashCommissionService commissionService,
                                          BidRepository bidRepository) {
        this.commissionService = commissionService;
        this.bidRepository     = bidRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidPaidByMobileMoney(BidPaidByMobileMoneyEvent event) {
        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("MobileMoneyCommissionListener: bid {} not found", event.getBidId());
            return;
        }

        // chargeCommissionAuto : wallet-first, fallback carte automatique, échec = créance.
        // Le trajet n'est pas annulé en cas d'échec (paiement principal déjà effectué).
        commissionService.chargeCommissionAuto(bid, event.getTravelerId());

        log.info("MobileMoneyCommissionListener: commission traitée pour bidId={} travelerId={}",
                event.getBidId(), event.getTravelerId());
    }
}
