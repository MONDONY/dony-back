package com.dony.api.payments;

import com.dony.api.common.AuditService;
import com.dony.api.payments.events.PaymentEscrowReadyEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NegotiationCaptureListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;

    private NegotiationCaptureListener listener;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new NegotiationCaptureListener(paymentRepository, auditService);
    }

    private PaymentEscrowReadyEvent event() {
        // Negotiation escrow-ready event carries a null bidId (payment keyed on the thread).
        return new PaymentEscrowReadyEvent(null, paymentId);
    }

    private PaymentEntity threadPayment(PaymentStatus status, boolean legacy, String chargeId) {
        PaymentEntity p = new PaymentEntity();
        p.setNegotiationThreadId(threadId);
        p.setStripePaymentIntentId("pi_xxx");
        p.setStatus(status);
        p.setLegacyDestinationCharge(legacy);
        p.setStripeChargeId(chargeId);
        p.setAmount(new BigDecimal("64.50"));
        p.setCommissionAmount(new BigDecimal("6.91"));
        // bidId left null on purpose — negotiation/thread payment
        return p;
    }

    private PaymentEntity bidPayment() {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID()); // classic bid flow — captured by BidAcceptedEventListener
        p.setStripePaymentIntentId("pi_bid");
        p.setStatus(PaymentStatus.ESCROW);
        return p;
    }

    @Test
    void captures_held_PI_when_escrow_ready() throws StripeException {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_existing");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markCapturedIfEscrow(any(), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            when(pi.capture()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.onEscrowReady(event());

            verify(pi).capture();
        }
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_CAPTURED_ON_PLATFORM"), any(), any());
    }

    @Test
    void persists_charge_id_when_absent() throws StripeException {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, null);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markCapturedIfEscrow(any(), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            when(pi.capture()).thenReturn(pi);
            when(pi.getLatestCharge()).thenReturn("ch_fresh");
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.onEscrowReady(event());
        }
        assertThat(p.getStripeChargeId()).isEqualTo("ch_fresh");
        verify(paymentRepository).save(p);
    }

    @Test
    void already_succeeded_PI_is_not_recaptured_but_marked() throws StripeException {
        // An already-captured PI (e.g. admin/manual capture) must not be captured again.
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_existing");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markCapturedIfEscrow(any(), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("succeeded");
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.onEscrowReady(event());

            verify(pi, never()).capture();
        }
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_CAPTURED_ON_PLATFORM"), any(), any());
    }

    @Test
    void skips_classic_bid_payment() {
        // Bid payments emit the same event but are captured by BidAcceptedEventListener.
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(bidPayment()));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            listener.onEscrowReady(event());
            mocked.verifyNoInteractions();
        }
        verify(paymentRepository, never()).markCapturedIfEscrow(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void no_op_when_payment_not_found() {
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());
        assertThatNoException().isThrownBy(() -> listener.onEscrowReady(event()));
        verifyNoInteractions(auditService);
        verify(paymentRepository, never()).markCapturedIfEscrow(any(), any());
    }

    @Test
    void skips_legacy_payment() {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, true, "ch_legacy");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            listener.onEscrowReady(event());
            mocked.verifyNoInteractions();
        }
        verify(paymentRepository, never()).markCapturedIfEscrow(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void skips_when_payment_not_in_escrow() {
        // Defensive: if the status isn't ESCROW (e.g. already RELEASED), do nothing.
        PaymentEntity p = threadPayment(PaymentStatus.RELEASED, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            listener.onEscrowReady(event());
            mocked.verifyNoInteractions();
        }
        verify(paymentRepository, never()).markCapturedIfEscrow(any(), any());
    }

    @Test
    void skips_when_already_captured_by_concurrent_run() {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markCapturedIfEscrow(any(), any())).thenReturn(0); // lost the CAS race

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            listener.onEscrowReady(event());
            mocked.verifyNoInteractions();
        }
        verify(auditService, never()).log(any(), any(), eq("PAYMENT_CAPTURED_ON_PLATFORM"), any(), any());
    }

    @Test
    void capture_failure_is_logged_not_thrown() throws StripeException {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markCapturedIfEscrow(any(), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            when(pi.capture()).thenThrow(mock(com.stripe.exception.InvalidRequestException.class));
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            assertThatNoException().isThrownBy(() -> listener.onEscrowReady(event()));
        }
        verify(auditService, never()).log(any(), any(), eq("PAYMENT_CAPTURED_ON_PLATFORM"), any(), any());
    }
}
