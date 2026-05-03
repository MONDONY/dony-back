package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidAcceptedEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;

    private BidAcceptedEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new BidAcceptedEventListener(paymentRepository, auditService);
    }

    private PaymentEntity paymentFor(UUID bidId, PaymentStatus status, boolean legacy) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setStripePaymentIntentId("pi_xxx");
        p.setStatus(status);
        p.setLegacyDestinationCharge(legacy);
        p.setAmount(new BigDecimal("30.00"));
        p.setCommissionAmount(new BigDecimal("3.60"));
        return p;
    }

    private BidAcceptedEvent eventFor(UUID bidId) {
        return new BidAcceptedEvent(bidId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void captures_PI_for_non_legacy_payment_in_escrow() throws StripeException {
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.ESCROW, false);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.capture()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.onBidAccepted(eventFor(bidId));

            verify(pi).capture();
            verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_CAPTURED_ON_PLATFORM"), any(), any());
        }
    }

    @Test
    void skips_capture_for_legacy_payment() {
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.ESCROW, true);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            listener.onBidAccepted(eventFor(bidId));
            mocked.verifyNoInteractions();
        }
        verify(auditService, never()).log(eq("PAYMENT"), any(), eq("PAYMENT_CAPTURED_ON_PLATFORM"), any(), any());
    }

    @Test
    void no_op_when_payment_not_found() {
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> listener.onBidAccepted(eventFor(bidId)));
        verifyNoInteractions(auditService);
    }

    @Test
    void no_op_when_status_not_escrow() {
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.RELEASED, false);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            listener.onBidAccepted(eventFor(bidId));
            mocked.verifyNoInteractions();
        }
    }

    @Test
    void capture_failure_is_logged_but_not_thrown() throws StripeException {
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.ESCROW, false);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.capture()).thenThrow(mock(com.stripe.exception.InvalidRequestException.class));
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            assertThatNoException().isThrownBy(() -> listener.onBidAccepted(eventFor(bidId)));
        }
    }
}
