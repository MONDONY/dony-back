package com.dony.api.payments;

import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.stripe.AdminAlertService;
import com.stripe.model.Capability;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceWebhookHandlerTest {

    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlert;

    PaymentService service;

    @BeforeEach
    void setUp() {
        service = PaymentServiceTestFactory.bare(paymentRepository, userRepository, auditService, adminAlert);
    }

    /** Build a mock Event backed by a mocked EventDataObjectDeserializer returning the given object. */
    private Event mockEvent(StripeObject obj) {
        Event event = mock(Event.class);
        EventDataObjectDeserializer deser = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deser);
        when(deser.getObject()).thenReturn(Optional.of(obj));
        return event;
    }

    /** Build a mock Event that returns Optional.empty() from getObject() — for raw JSON fallback paths. */
    private Event mockEventNoObject(String rawJson) {
        Event event = mock(Event.class);
        EventDataObjectDeserializer deser = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deser);
        when(deser.getObject()).thenReturn(Optional.empty());
        lenient().when(deser.getRawJson()).thenReturn(rawJson);
        return event;
    }

    // ── handlePaymentIntentCanceled ───────────────────────────────────────────

    @Test
    void handlePaymentIntentCanceled_cancelsEscrowPayment() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_test_123");

        var payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.findByStripePaymentIntentId("pi_test_123")).thenReturn(Optional.of(payment));

        service.handlePaymentIntentCanceled(mockEvent(pi));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(paymentRepository).save(payment);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_INTENT_CANCELED"), any(), any());
    }

    @Test
    void handlePaymentIntentCanceled_cancelsPendingPayment() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_test_pending");

        var payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByStripePaymentIntentId("pi_test_pending")).thenReturn(Optional.of(payment));

        service.handlePaymentIntentCanceled(mockEvent(pi));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(paymentRepository).save(payment);
    }

    @Test
    void handlePaymentIntentCanceled_doesNotCancelReleasedPayment() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_test_456");

        var payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.RELEASED);
        when(paymentRepository.findByStripePaymentIntentId("pi_test_456")).thenReturn(Optional.of(payment));

        service.handlePaymentIntentCanceled(mockEvent(pi));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        verify(paymentRepository, never()).save(any());
    }

    // ── handleAccountDeauthorized ─────────────────────────────────────────────
    // The handler has a typed path (getObject → Account) with raw JSON fallback.
    // In unit tests the Stripe SDK cannot fully deserialize typed objects (no API version context),
    // so the typed getObject() check produces a null accountId and the code falls through to the
    // raw JSON path. We test via mockEventNoObject (empty Optional) to exercise the fallback path,
    // which is the path that actually runs in test context.

    @Test
    void handleAccountDeauthorized_setsUserDisabled() {
        var user = new UserEntity();
        PaymentServiceTestFactory.setId(user, java.util.UUID.randomUUID());
        when(userRepository.findByStripeAccountId("acct_test_001")).thenReturn(Optional.of(user));

        service.handleAccountDeauthorized(
                mockEventNoObject("{\"id\":\"acct_test_001\",\"object\":\"account\"}"));

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.DISABLED);
        verify(userRepository).save(user);
        verify(adminAlert).raise(eq("STRIPE_ACCOUNT_DEAUTHORIZED"), any(), any());
        verify(auditService).log(eq("USER"), any(), eq("STRIPE_ACCOUNT_DEAUTHORIZED"), any(), any());
    }

    @Test
    void handleAccountDeauthorized_logsWarnWhenUserNotFound() {
        service.handleAccountDeauthorized(
                mockEventNoObject("{\"id\":\"acct_unknown\",\"object\":\"account\"}"));

        verify(userRepository, never()).save(any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    // ── handleCapabilityUpdated ───────────────────────────────────────────────

    @Test
    void handleCapabilityUpdated_setsDisabledOnInactiveTransfers() {
        Capability cap = mock(Capability.class);
        when(cap.getId()).thenReturn("transfers");
        when(cap.getStatus()).thenReturn("inactive");
        when(cap.getAccount()).thenReturn("acct_cap_001");

        var user = new UserEntity();
        when(userRepository.findByStripeAccountId("acct_cap_001")).thenReturn(Optional.of(user));

        service.handleCapabilityUpdated(mockEvent(cap));

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.DISABLED);
        verify(userRepository).save(user);
        verify(adminAlert).raise(eq("STRIPE_CAPABILITY_LOST"), any(), any());
        verify(auditService).log(eq("USER"), any(), eq("STRIPE_CAPABILITY_LOST"), any(), any());
    }

    @Test
    void handleCapabilityUpdated_setsDisabledOnInactiveCardPayments() {
        Capability cap = mock(Capability.class);
        when(cap.getId()).thenReturn("card_payments");
        when(cap.getStatus()).thenReturn("inactive");
        when(cap.getAccount()).thenReturn("acct_cap_004");

        var user = new UserEntity();
        when(userRepository.findByStripeAccountId("acct_cap_004")).thenReturn(Optional.of(user));

        service.handleCapabilityUpdated(mockEvent(cap));

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.DISABLED);
        verify(adminAlert).raise(eq("STRIPE_CAPABILITY_LOST"), any(), any());
    }

    @Test
    void handleCapabilityUpdated_doesNotDisableOnPending() {
        Capability cap = mock(Capability.class);
        when(cap.getId()).thenReturn("transfers");
        when(cap.getStatus()).thenReturn("pending");
        when(cap.getAccount()).thenReturn("acct_cap_002");

        service.handleCapabilityUpdated(mockEvent(cap));

        verify(userRepository, never()).findByStripeAccountId(any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    @Test
    void handleCapabilityUpdated_doesNotDisableOnActive() {
        Capability cap = mock(Capability.class);
        when(cap.getId()).thenReturn("card_payments");
        when(cap.getStatus()).thenReturn("active");
        when(cap.getAccount()).thenReturn("acct_cap_003");

        service.handleCapabilityUpdated(mockEvent(cap));

        verify(userRepository, never()).findByStripeAccountId(any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    // ── handleChargeRefunded ──────────────────────────────────────────────────
    // Stripe charge.amount_refunded est ABSOLU et CUMULÉ (en cents). Le handler est
    // une machine à états sur les montants absolus : enregistre toujours refundedAmount,
    // ne réécrit jamais un paiement RELEASED, trace les refunds partiels, idempotent au replay.

    /** Build a mock Charge with the given paymentIntent, total amount and cumulative amount_refunded (cents). */
    private Charge mockCharge(String piId, long amount, long amountRefunded) {
        Charge charge = mock(Charge.class);
        when(charge.getPaymentIntent()).thenReturn(piId);
        lenient().when(charge.getAmount()).thenReturn(amount);
        lenient().when(charge.getAmountRefunded()).thenReturn(amountRefunded);
        return charge;
    }

    @Test
    void handleChargeRefunded_fullRefundOnEscrow_setsRefundedAndStatus() {
        Charge charge = mockCharge("pi_refund_full", 3000L, 3000L);

        var payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, java.util.UUID.randomUUID());
        payment.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund_full")).thenReturn(Optional.of(payment));

        service.handleChargeRefunded(mockEvent(charge));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        verify(paymentRepository).save(payment);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED"), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    @Test
    void handleChargeRefunded_partialRefundOnEscrow_keepsStatusRecordsAmount() {
        Charge charge = mockCharge("pi_refund_partial", 3000L, 1000L);

        var payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, java.util.UUID.randomUUID());
        payment.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund_partial")).thenReturn(Optional.of(payment));

        service.handleChargeRefunded(mockEvent(charge));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        verify(paymentRepository).save(payment);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_PARTIALLY_REFUNDED"), any(), any());
        verify(auditService, never()).log(any(), any(), eq("PAYMENT_REFUNDED"), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    @Test
    void handleChargeRefunded_onReleased_keepsStatusRaisesAdminAlert() {
        Charge charge = mockCharge("pi_refund_released", 3000L, 3000L);

        var payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, java.util.UUID.randomUUID());
        payment.setStatus(PaymentStatus.RELEASED);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund_released")).thenReturn(Optional.of(payment));

        service.handleChargeRefunded(mockEvent(charge));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        verify(paymentRepository).save(payment);
        verify(adminAlert).raise(eq("REFUND_AFTER_RELEASE"), any(), any());
        verify(auditService).log(eq("PAYMENT"), any(), eq("REFUND_AFTER_RELEASE"), any(), any());
        verify(auditService, never()).log(any(), any(), eq("PAYMENT_REFUNDED"), any(), any());
    }

    @Test
    void handleChargeRefunded_replaySameWebhook_idempotentNoDuplicateAlert() {
        Charge charge = mockCharge("pi_refund_replay", 3000L, 3000L);

        var payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, java.util.UUID.randomUUID());
        payment.setStatus(PaymentStatus.RELEASED);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund_replay")).thenReturn(Optional.of(payment));

        // First delivery → records amount + raises alert once.
        service.handleChargeRefunded(mockEvent(charge));
        // Replay identical webhook → alreadyRecorded → no duplicate alert/audit.
        service.handleChargeRefunded(mockEvent(charge));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        verify(adminAlert, times(1)).raise(eq("REFUND_AFTER_RELEASE"), any(), any());
        verify(auditService, times(1)).log(eq("PAYMENT"), any(), eq("REFUND_AFTER_RELEASE"), any(), any());
    }

    @Test
    void handleChargeRefunded_alreadyRefunded_updatesAmountNoStatusChange() {
        Charge charge = mockCharge("pi_refund_already", 3000L, 3000L);

        var payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, java.util.UUID.randomUUID());
        payment.setStatus(PaymentStatus.REFUNDED);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund_already")).thenReturn(Optional.of(payment));

        service.handleChargeRefunded(mockEvent(charge));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
        verify(paymentRepository).save(payment);
        // Déjà REFUNDED : pas de nouvel audit PAYMENT_REFUNDED, pas d'alerte.
        verify(auditService, never()).log(any(), any(), eq("PAYMENT_REFUNDED"), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }
}
