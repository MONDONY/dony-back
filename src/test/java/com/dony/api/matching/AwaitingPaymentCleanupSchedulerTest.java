package com.dony.api.matching;

import com.dony.api.payments.PaymentService;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwaitingPaymentCleanupSchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private PaymentService paymentService;

    private AwaitingPaymentCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AwaitingPaymentCleanupScheduler(bidRepository, paymentService);
    }

    private BidEntity expired(String piId) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setStatus(BidStatus.AWAITING_PAYMENT);
        b.setPaymentIntentId(piId);
        b.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        return b;
    }

    @Test
    void deletes_bid_when_cancel_succeeds() throws StripeException {
        BidEntity bid = expired("pi_xxx");
        when(bidRepository.findByStatusAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), any())).thenReturn(List.of(bid));

        scheduler.cleanupUnpaidBids();

        verify(paymentService).cancelPaymentIntent("pi_xxx");
        verify(bidRepository).deleteById(bid.getId());
        verify(paymentService, never()).promoteBidOnPaymentAuthorized(any());
    }

    @Test
    void promotes_when_PI_already_succeeded_race_condition() throws StripeException {
        BidEntity bid = expired("pi_race");
        when(bidRepository.findByStatusAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), any())).thenReturn(List.of(bid));

        InvalidRequestException ex = mock(InvalidRequestException.class);
        when(ex.getCode()).thenReturn("payment_intent_unexpected_state");
        doThrow(ex).when(paymentService).cancelPaymentIntent("pi_race");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            // requires_capture or succeeded both should trigger promotion
            when(pi.getStatus()).thenReturn("requires_capture");
            piStatic.when(() -> PaymentIntent.retrieve("pi_race")).thenReturn(pi);

            scheduler.cleanupUnpaidBids();
        }

        verify(paymentService).promoteBidOnPaymentAuthorized("pi_race");
        verify(bidRepository, never()).deleteById(bid.getId());
    }

    @Test
    void leaves_bid_alone_on_generic_stripe_error() throws StripeException {
        BidEntity bid = expired("pi_err");
        when(bidRepository.findByStatusAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), any())).thenReturn(List.of(bid));

        InvalidRequestException ex = mock(InvalidRequestException.class);
        when(ex.getCode()).thenReturn("rate_limit");
        doThrow(ex).when(paymentService).cancelPaymentIntent("pi_err");

        scheduler.cleanupUnpaidBids();  // should NOT throw

        verify(bidRepository, never()).deleteById(bid.getId());
        verify(paymentService, never()).promoteBidOnPaymentAuthorized(any());
    }

    @Test
    void no_op_when_no_expired_bids() {
        when(bidRepository.findByStatusAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), any())).thenReturn(List.of());

        scheduler.cleanupUnpaidBids();

        verifyNoInteractions(paymentService);
        verify(bidRepository, never()).deleteById(any());
    }
}
