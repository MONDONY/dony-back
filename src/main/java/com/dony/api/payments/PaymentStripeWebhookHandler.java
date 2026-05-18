package com.dony.api.payments;

import com.dony.api.common.stripe.StripeWebhookHandler;
import com.dony.api.payments.cash.CashCommissionWebhookHandler;
import com.stripe.model.Event;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class PaymentStripeWebhookHandler implements StripeWebhookHandler {

    private static final Set<String> SUPPORTED = Set.of(
            "account.updated",
            "payment_intent.amount_capturable_updated",
            "payment_intent.payment_failed",
            "charge.refunded",
            "setup_intent.succeeded",
            "payment_intent.succeeded",
            "payment_method.detached"
    );

    private final PaymentService paymentService;
    private final CashCommissionWebhookHandler cashHandler;

    public PaymentStripeWebhookHandler(PaymentService paymentService,
                                        CashCommissionWebhookHandler cashHandler) {
        this.paymentService = paymentService;
        this.cashHandler = cashHandler;
    }

    @Override
    public boolean supports(String eventType) {
        return SUPPORTED.contains(eventType);
    }

    @Override
    public void handle(Event event) {
        switch (event.getType()) {
            case "account.updated"                          -> paymentService.handleAccountUpdated(event);
            case "payment_intent.amount_capturable_updated" -> paymentService.handlePaymentEscrowActive(event);
            case "payment_intent.payment_failed" -> {
                paymentService.handlePaymentFailed(event);
                cashHandler.handlePaymentIntentFailed(event);
            }
            case "charge.refunded"          -> paymentService.handleChargeRefunded(event);
            case "setup_intent.succeeded"   -> cashHandler.handleSetupIntentSucceeded(event);
            case "payment_intent.succeeded" -> cashHandler.handlePaymentIntentSucceeded(event);
            case "payment_method.detached"  -> cashHandler.handlePaymentMethodDetached(event);
        }
    }
}
