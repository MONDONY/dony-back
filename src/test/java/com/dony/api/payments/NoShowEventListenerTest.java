package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import com.stripe.exception.ApiException;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoShowEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;

    private NoShowEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new NoShowEventListener(paymentRepository, auditService);
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

    private VoyageurNoShowEvent eventFor(UUID bidId) {
        return new VoyageurNoShowEvent(bidId, UUID.randomUUID(), UUID.randomUUID(), 1);
    }

    @Test
    void refunds_when_payment_escrow() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(any())).thenReturn(1);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class)))
                    .thenReturn(mock(Refund.class));

            listener.onVoyageurNoShow(eventFor(p.getBidId()));

            refundStatic.verify(() -> Refund.create(any(RefundCreateParams.class)));
        }
        verify(paymentRepository).markRefundedIfEscrow(any());
        verify(paymentRepository, never()).save(any());
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED_NO_SHOW"),
                any(), any(Map.class));
    }

    @Test
    void no_op_when_payment_not_escrow() {
        PaymentEntity p = payment(PaymentStatus.RELEASED);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            listener.onVoyageurNoShow(eventFor(p.getBidId()));
            refundStatic.verifyNoInteractions();
        }
        verify(paymentRepository, never()).markRefundedIfEscrow(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void refund_skipped_when_atomic_claim_lost() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(any())).thenReturn(0);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            listener.onVoyageurNoShow(eventFor(p.getBidId()));
            refundStatic.verifyNoInteractions();
        }
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void refund_stripe_failure_rolls_back_claim() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(any())).thenReturn(1);

        StripeException stripeEx = new ApiException("boom", null, null, 500, null);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class))).thenThrow(stripeEx);

            assertThatThrownBy(() -> listener.onVoyageurNoShow(eventFor(p.getBidId())))
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
            listener.onVoyageurNoShow(eventFor(bidId));
            refundStatic.verifyNoInteractions();
        }
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }
}
