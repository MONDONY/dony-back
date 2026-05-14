package com.dony.api.payments.cash;

import com.dony.api.cancellation.CancellationReason;
import com.dony.api.cancellation.events.CancellationConfirmedEvent;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CommissionRefundListener {

    private final CashCommissionService cashCommissionService;
    private final BidRepository bidRepository;

    public CommissionRefundListener(CashCommissionService cashCommissionService,
                                    BidRepository bidRepository) {
        this.cashCommissionService = cashCommissionService;
        this.bidRepository = bidRepository;
    }

    @EventListener
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        if (event.reason() != CancellationReason.SENDER_NO_SHOW) return;

        BidEntity bid = bidRepository.findById(event.bidId()).orElse(null);
        if (bid == null) return;
        if (bid.getPaymentMethod() != com.dony.api.payments.cash.PaymentMethod.CASH) return;
        if (bid.getCommissionStatus() != CommissionStatus.CHARGED) return;

        cashCommissionService.refundCommission(bid);
    }
}
