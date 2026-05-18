package com.dony.api.payments;

import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.stripe.AdminAlertService;
import com.stripe.model.Capability;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
