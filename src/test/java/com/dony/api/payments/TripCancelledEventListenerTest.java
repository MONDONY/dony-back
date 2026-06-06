package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.cancellation.events.TripCancelledEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripCancelledEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;
    @Mock private BidRepository bidRepository;

    private TripCancelledEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new TripCancelledEventListener(paymentRepository, auditService, bidRepository);
    }

    private PaymentEntity payment(PaymentStatus status, UUID bidId, UUID threadId) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setStripePaymentIntentId("pi_xxx");
        p.setStatus(status);
        p.setAmount(new BigDecimal("80.00"));
        p.setCommissionAmount(new BigDecimal("8.57"));
        return p;
    }

    private TripCancelledEvent event(UUID bidId) {
        return new TripCancelledEvent(UUID.randomUUID(), UUID.randomUUID(),
                List.of(UUID.randomUUID()), "vol annulé", List.of(bidId));
    }

    @Test
    void negotiation_thread_payment_refunded_via_thread_fallback() {
        // Regression: a cancelled negotiation/dedicated trip keys its escrow on the thread
        // (bid_id NULL). findByBidId returns empty → the sender used to never be refunded.
        UUID bidId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();

        BidEntity bid = new BidEntity();
        bid.setLinkedNegotiationThreadId(threadId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        PaymentEntity p = payment(PaymentStatus.ESCROW, null, threadId);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(p));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            ArgumentCaptor<RefundCreateParams> captor = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(captor.capture())).thenReturn(mock(Refund.class));

            listener.handleTripCancelled(event(bidId));

            assertThat(captor.getValue().getPaymentIntent()).isEqualTo("pi_xxx");
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED"), any(), any());
    }

    @Test
    void classic_bid_payment_escrow_is_refunded() {
        UUID bidId = UUID.randomUUID();
        PaymentEntity p = payment(PaymentStatus.ESCROW, bidId, null);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(p));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class))).thenReturn(mock(Refund.class));
            listener.handleTripCancelled(event(bidId));
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void pending_payment_intent_is_cancelled() throws StripeException {
        UUID bidId = UUID.randomUUID();
        PaymentEntity p = payment(PaymentStatus.PENDING, bidId, null);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel(any(com.stripe.param.PaymentIntentCancelParams.class))).thenReturn(pi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.handleTripCancelled(event(bidId));
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void no_payment_anywhere_is_a_no_op() {
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> listener.handleTripCancelled(event(bidId)));
        verifyNoInteractions(auditService);
    }
}
