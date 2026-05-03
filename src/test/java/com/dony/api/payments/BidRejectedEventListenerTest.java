package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.BidRejectedEvent;
import com.stripe.exception.ApiException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidRejectedEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;

    private BidRejectedEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new BidRejectedEventListener(paymentRepository, auditService);
    }

    private PaymentEntity payment(PaymentStatus status, boolean legacy) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setStripePaymentIntentId("pi_xxx");
        p.setStatus(status);
        p.setLegacyDestinationCharge(legacy);
        p.setAmount(new BigDecimal("30.00"));
        p.setCommissionAmount(new BigDecimal("3.60"));
        return p;
    }

    private BidRejectedEvent eventFor(UUID bidId) {
        return new BidRejectedEvent(bidId, UUID.randomUUID(), "test reason");
    }

    @Test
    void cancels_when_payment_pending() throws Exception {
        PaymentEntity p = payment(PaymentStatus.PENDING, false);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel(any(PaymentIntentCancelParams.class))).thenReturn(pi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.handleBidRejected(eventFor(p.getBidId()));

            verify(pi).cancel(any(PaymentIntentCancelParams.class));
            refundStatic.verifyNoInteractions();
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentRepository).save(p);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_CANCELLED_BID_REJECTED"),
                any(), any(Map.class));
    }

    @Test
    void refunds_when_payment_escrow_legacy() {
        PaymentEntity p = payment(PaymentStatus.ESCROW, true);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class)))
                    .thenReturn(mock(Refund.class));

            listener.handleBidRejected(eventFor(p.getBidId()));

            refundStatic.verify(() -> Refund.create(any(RefundCreateParams.class)));
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentRepository).save(p);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED_BID_REJECTED"),
                any(), any(Map.class));
    }

    @Test
    void refunds_when_payment_escrow_v2_captured_on_platform() {
        PaymentEntity p = payment(PaymentStatus.ESCROW, false); // v2 — captured on platform
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class)))
                    .thenReturn(mock(Refund.class));

            listener.handleBidRejected(eventFor(p.getBidId()));

            refundStatic.verify(() -> Refund.create(any(RefundCreateParams.class)));
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentRepository).save(p);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED_BID_REJECTED"),
                any(), any(Map.class));
    }

    @Test
    void no_op_when_payment_already_released() {
        PaymentEntity p = payment(PaymentStatus.RELEASED, false);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            listener.handleBidRejected(eventFor(p.getBidId()));
            piStatic.verifyNoInteractions();
            refundStatic.verifyNoInteractions();
        }
        // Statut inchangé, aucun side effect
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        verify(paymentRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void cancel_pending_swallows_stripe_exception() {
        PaymentEntity p = payment(PaymentStatus.PENDING, false);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        StripeException stripeEx = new ApiException("boom", null, null, 500, null);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenThrow(stripeEx);

            listener.handleBidRejected(eventFor(p.getBidId()));
        }
        // statut inchangé : on ne sauvegarde pas, on n'audite pas
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(paymentRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void refund_escrow_swallows_stripe_exception() {
        PaymentEntity p = payment(PaymentStatus.ESCROW, false);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        StripeException stripeEx = new ApiException("boom", null, null, 500, null);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class))).thenThrow(stripeEx);

            listener.handleBidRejected(eventFor(p.getBidId()));
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        verify(paymentRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void no_op_when_payment_not_found() {
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            listener.handleBidRejected(eventFor(bidId));
            piStatic.verifyNoInteractions();
            refundStatic.verifyNoInteractions();
        }
        verify(paymentRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }
}
