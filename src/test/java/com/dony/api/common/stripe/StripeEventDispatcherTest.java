package com.dony.api.common.stripe;

import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StripeEventDispatcherTest {

    @Mock StripeWebhookHandler paymentHandler;
    @Mock StripeWebhookHandler kycHandler;
    StripeEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new StripeEventDispatcher(List.of(paymentHandler, kycHandler));
    }

    @Test
    void dispatch_callsMatchingHandler() {
        when(paymentHandler.supports("payment_intent.succeeded")).thenReturn(true);
        String payload = "{\"id\":\"evt_1\",\"object\":\"event\","
                + "\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{}}}";

        boolean handled = dispatcher.dispatch(payload);

        assertThat(handled).isTrue();
        verify(paymentHandler).handle(any());
        verify(kycHandler, never()).handle(any());
    }

    @Test
    void dispatch_returnsFalse_whenNoHandlerSupports() {
        when(paymentHandler.supports(any())).thenReturn(false);
        when(kycHandler.supports(any())).thenReturn(false);
        String payload = "{\"id\":\"evt_2\",\"object\":\"event\","
                + "\"type\":\"unknown.event\",\"data\":{\"object\":{}}}";

        boolean handled = dispatcher.dispatch(payload);

        assertThat(handled).isFalse();
        verify(paymentHandler, never()).handle(any());
    }
}
