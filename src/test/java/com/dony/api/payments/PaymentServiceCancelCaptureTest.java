package com.dony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

class PaymentServiceCancelCaptureTest {

    private final PaymentService service = PaymentServiceTestFactory.bare();

    @Test
    void cancelPaymentIntent_calls_stripe_cancel() throws StripeException {
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_123")).thenReturn(pi);

            assertThatNoException().isThrownBy(() -> service.cancelPaymentIntent("pi_123"));
            verify(pi).cancel();
        }
    }

    @Test
    void capturePaymentIntent_calls_stripe_capture() throws StripeException {
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.capture()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_456")).thenReturn(pi);

            assertThatNoException().isThrownBy(() -> service.capturePaymentIntent("pi_456"));
            verify(pi).capture();
        }
    }

    @Test
    void cancelPaymentIntent_with_null_does_nothing() {
        assertThatNoException().isThrownBy(() -> service.cancelPaymentIntent(null));
    }
}
