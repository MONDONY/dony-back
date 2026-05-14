package com.dony.api.payments.cash;

import com.dony.api.payments.cash.event.BidAcceptanceRequestedEvent;
import com.dony.api.payments.cash.event.CommissionRefundRequested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CashPaymentEventsTest {

    @Test
    void bidAcceptanceRequestedEvent_fieldsAccessible() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        BidAcceptanceRequestedEvent event = new BidAcceptanceRequestedEvent(bidId, travelerId, PaymentMethod.CASH);

        assertThat(event.bidId()).isEqualTo(bidId);
        assertThat(event.travelerId()).isEqualTo(travelerId);
        assertThat(event.paymentMethod()).isEqualTo(PaymentMethod.CASH);
    }

    @Test
    void commissionRefundRequested_fieldsAccessible() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        CommissionRefundRequested event = new CommissionRefundRequested(bidId, travelerId, "no_show");

        assertThat(event.bidId()).isEqualTo(bidId);
        assertThat(event.travelerId()).isEqualTo(travelerId);
        assertThat(event.reason()).isEqualTo("no_show");
    }
}
