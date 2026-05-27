package com.dony.api.payments.mobilemoney;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.CashCommissionService;
import com.dony.api.payments.mobilemoney.events.BidPaidByMobileMoneyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
public class MobileMoneyCommissionListener {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyCommissionListener.class);

    private final CashCommissionService commissionService;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public MobileMoneyCommissionListener(CashCommissionService commissionService,
                                          BidRepository bidRepository,
                                          UserRepository userRepository,
                                          AuditService auditService) {
        this.commissionService = commissionService;
        this.bidRepository     = bidRepository;
        this.userRepository    = userRepository;
        this.auditService      = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidPaidByMobileMoney(BidPaidByMobileMoneyEvent event) {
        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("MobileMoneyCommissionListener: bid {} not found", event.getBidId());
            return;
        }

        UserEntity traveler = userRepository.findById(event.getTravelerId()).orElse(null);
        if (traveler == null) {
            log.warn("MobileMoneyCommissionListener: traveler {} not found", event.getTravelerId());
            return;
        }

        if (traveler.getCommissionPaymentMethodId() == null) {
            log.warn("MobileMoneyCommissionListener: traveler {} has no commission card — skipping",
                    event.getTravelerId());
            return;
        }

        try {
            BigDecimal commissionCharged = commissionService.chargeCommissionForMobileMoney(bid, event.getTravelerId());
            Map<String, Object> auditPayload = new HashMap<>();
            auditPayload.put("amount", commissionCharged.toPlainString());
            auditService.log("MM_COMMISSION", bid.getId(), "COMMISSION_CHARGED",
                    event.getTravelerId(), auditPayload);
            log.info("MobileMoneyCommissionListener: commission {} charged for bidId={}",
                    commissionCharged, event.getBidId());
        } catch (Exception e) {
            log.error("MobileMoneyCommissionListener: commission charge failed for bidId={}",
                    event.getBidId(), e);
        }
    }
}
