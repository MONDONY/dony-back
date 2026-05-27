package com.dony.api.payments;

import com.dony.api.payments.chargeback.ChargebackService;
import com.dony.api.payments.cash.CashCommissionWebhookHandler;
import com.dony.api.payments.wallet.WalletService;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentStripeWebhookHandlerNewEventsTest {

    @Mock PaymentService paymentService;
    @Mock CashCommissionWebhookHandler cashHandler;
    @Mock ChargebackService chargebackService;
    @Mock WalletService walletService;
    PaymentStripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentStripeWebhookHandler(paymentService, cashHandler, chargebackService, walletService);
    }

    private Event evt(String type) {
        return ApiResource.GSON.fromJson(
            "{\"id\":\"e\",\"object\":\"event\",\"type\":\"" + type + "\",\"data\":{\"object\":{}}}",
            Event.class);
    }

    @Test void handles_paymentIntentCanceled() { handler.handle(evt("payment_intent.canceled")); verify(paymentService).handlePaymentIntentCanceled(any()); }
    @Test void handles_transferCreated()       { handler.handle(evt("transfer.created")); verify(paymentService).handleTransferCreated(any()); }
    @Test void handles_transferReversed()      { handler.handle(evt("transfer.reversed")); verify(paymentService).handleTransferReversed(any()); }
    @Test void handles_transferUpdated()       { handler.handle(evt("transfer.updated")); verify(paymentService).handleTransferUpdated(any()); }
    @Test void handles_payoutFailed()          { handler.handle(evt("payout.failed")); verify(paymentService).handlePayoutFailed(any()); }
    @Test void handles_payoutPaid()            { handler.handle(evt("payout.paid")); verify(paymentService).handlePayoutPaid(any()); }
}
