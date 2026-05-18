package com.dony.api.payments;

import com.dony.api.payments.cash.CashCommissionWebhookHandler;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentStripeWebhookHandlerTest {

    @Mock PaymentService paymentService;
    @Mock CashCommissionWebhookHandler cashHandler;
    PaymentStripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentStripeWebhookHandler(paymentService, cashHandler);
    }

    private Event buildEvent(String type) {
        String json = String.format(
            "{\"id\":\"evt_x\",\"object\":\"event\",\"type\":\"%s\"," +
            "\"data\":{\"object\":{}}}", type);
        return ApiResource.GSON.fromJson(json, Event.class);
    }

    @Test
    void supports_trueForPaymentEvents() {
        assertThat(handler.supports("account.updated")).isTrue();
        assertThat(handler.supports("payment_intent.amount_capturable_updated")).isTrue();
        assertThat(handler.supports("payment_intent.payment_failed")).isTrue();
        assertThat(handler.supports("charge.refunded")).isTrue();
        assertThat(handler.supports("setup_intent.succeeded")).isTrue();
        assertThat(handler.supports("payment_intent.succeeded")).isTrue();
        assertThat(handler.supports("payment_method.detached")).isTrue();
        assertThat(handler.supports("identity.verification_session.verified")).isFalse();
        assertThat(handler.supports("unknown.event")).isFalse();
    }

    @Test
    void handle_accountUpdated_callsService() {
        handler.handle(buildEvent("account.updated"));
        verify(paymentService).handleAccountUpdated(any());
    }

    @Test
    void handle_paymentEscrowActive_callsService() {
        handler.handle(buildEvent("payment_intent.amount_capturable_updated"));
        verify(paymentService).handlePaymentEscrowActive(any());
    }

    @Test
    void handle_paymentFailed_callsBothHandlers() {
        handler.handle(buildEvent("payment_intent.payment_failed"));
        verify(paymentService).handlePaymentFailed(any());
        verify(cashHandler).handlePaymentIntentFailed(any());
    }

    @Test
    void handle_chargeRefunded_callsService() {
        handler.handle(buildEvent("charge.refunded"));
        verify(paymentService).handleChargeRefunded(any());
    }

    @Test
    void handle_setupIntentSucceeded_callsCashHandler() {
        handler.handle(buildEvent("setup_intent.succeeded"));
        verify(cashHandler).handleSetupIntentSucceeded(any());
    }

    @Test
    void handle_paymentIntentSucceeded_callsCashHandler() {
        handler.handle(buildEvent("payment_intent.succeeded"));
        verify(cashHandler).handlePaymentIntentSucceeded(any());
    }

    @Test
    void handle_paymentMethodDetached_callsCashHandler() {
        handler.handle(buildEvent("payment_method.detached"));
        verify(cashHandler).handlePaymentMethodDetached(any());
    }
}
