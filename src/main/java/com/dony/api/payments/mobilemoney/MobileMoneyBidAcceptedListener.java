package com.dony.api.payments.mobilemoney;

import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.notifications.NotificationDispatcher;
import com.dony.api.payments.cash.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Set;

@Component
public class MobileMoneyBidAcceptedListener {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyBidAcceptedListener.class);

    private static final Set<PaymentMethod> MM_PROVIDERS =
            Set.of(PaymentMethod.WAVE, PaymentMethod.ORANGE_MONEY);

    private final MobileMoneyPaymentService mmPaymentService;
    private final BidRepository bidRepository;
    private final NotificationDispatcher notificationDispatcher;

    public MobileMoneyBidAcceptedListener(MobileMoneyPaymentService mmPaymentService,
                                           BidRepository bidRepository,
                                           NotificationDispatcher notificationDispatcher) {
        this.mmPaymentService      = mmPaymentService;
        this.bidRepository         = bidRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidAccepted(BidAcceptedEvent event) {
        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("MobileMoneyBidAcceptedListener: bid {} not found", event.getBidId());
            return;
        }

        PaymentMethod pm = bid.getPaymentMethod();
        if (!MM_PROVIDERS.contains(pm)) {
            return;
        }

        try {
            MobileMoneyPaymentEntity mmPayment = mmPaymentService.initiate(event.getBidId());
            notificationDispatcher.notifyUser(
                    event.getSenderId(),
                    "Payez votre trajet",
                    "Le voyageur a accepté. Cliquez pour payer via " + pm.name(),
                    Map.of("type", "MM_PAYMENT_PENDING",
                           "bidId", event.getBidId().toString(),
                           "paymentLink", mmPayment.getPaymentLink() != null ? mmPayment.getPaymentLink() : "")
            );
            log.info("MobileMoneyBidAcceptedListener: initiated MM payment for bidId={} provider={}",
                    event.getBidId(), pm);
        } catch (Exception e) {
            log.error("MobileMoneyBidAcceptedListener: failed to initiate MM for bidId={}",
                    event.getBidId(), e);
        }
    }
}
