package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.ParcelRefusedEvent;
import com.stripe.exception.ApiException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParcelRefusedEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;

    private ParcelRefusedEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new ParcelRefusedEventListener(paymentRepository, auditService);
    }

    private PaymentEntity payment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setStripePaymentIntentId("pi_xxx");
        p.setStatus(status);
        p.setAmount(new BigDecimal("30.00"));
        p.setCommissionAmount(new BigDecimal("3.60"));
        return p;
    }

    private ParcelRefusedEvent eventFor(UUID bidId) {
        return new ParcelRefusedEvent(bidId, UUID.randomUUID(), UUID.randomUUID(), "colis abîmé");
    }

    @Test
    void cancels_payment_intent_when_payment_pending() throws Exception {
        // Régression : un PI non encore capturé doit être ANNULÉ, pas remboursé
        // (Refund.create échoue sur un PI non capturé).
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel(any(PaymentIntentCancelParams.class))).thenReturn(pi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.onParcelRefused(eventFor(p.getBidId()));

            verify(pi).cancel(any(PaymentIntentCancelParams.class));
            refundStatic.verifyNoInteractions();
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentRepository).save(p);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_CANCELLED_PARCEL_REFUSED"),
                any(), any(Map.class));
    }

    @Test
    void refunds_when_payment_escrow() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(any())).thenReturn(1);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class)))
                    .thenReturn(mock(Refund.class));

            listener.onParcelRefused(eventFor(p.getBidId()));

            refundStatic.verify(() -> Refund.create(any(RefundCreateParams.class)));
        }
        verify(paymentRepository).markRefundedIfEscrow(any());
        verify(paymentRepository, never()).save(any());
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED_PARCEL_REFUSED"),
                any(), any(Map.class));
    }

    @Test
    void no_op_when_payment_released() {
        // Régression : un paiement RELEASED (fonds déjà transférés au voyageur) ne doit
        // JAMAIS être remboursé — double sortie d'argent sinon.
        PaymentEntity p = payment(PaymentStatus.RELEASED);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            listener.onParcelRefused(eventFor(p.getBidId()));
            piStatic.verifyNoInteractions();
            refundStatic.verifyNoInteractions();
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        verify(paymentRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void cancel_pending_swallows_stripe_exception() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        StripeException stripeEx = new ApiException("boom", null, null, 500, null);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenThrow(stripeEx);

            listener.onParcelRefused(eventFor(p.getBidId()));
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(paymentRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void refund_skipped_when_atomic_claim_lost() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(any())).thenReturn(0);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            listener.onParcelRefused(eventFor(p.getBidId()));
            refundStatic.verifyNoInteractions();
        }
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void refund_escrow_stripe_failure_rolls_back_claim() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(any())).thenReturn(1);

        StripeException stripeEx = new ApiException("boom", null, null, 500, null);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class))).thenThrow(stripeEx);

            assertThatThrownBy(() -> listener.onParcelRefused(eventFor(p.getBidId())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCause(stripeEx);
        }
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void no_op_when_payment_not_found() {
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            listener.onParcelRefused(eventFor(bidId));
            refundStatic.verifyNoInteractions();
        }
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }
}
