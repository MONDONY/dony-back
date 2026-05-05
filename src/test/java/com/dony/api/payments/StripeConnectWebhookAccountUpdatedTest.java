package com.dony.api.payments;

import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.events.StripeOnboardingCompletedEvent;
import com.stripe.model.Account;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the handleAccountUpdated webhook handler.
 * Tests cover all status transitions: ONBOARDING_COMPLETE, REJECTED, DISABLED,
 * idempotency guard (event published only once), and no-change early return.
 */
@ExtendWith(MockitoExtension.class)
class StripeConnectWebhookAccountUpdatedTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    PaymentService service;

    private final UUID userId = UUID.randomUUID();
    private static final String ACCOUNT_ID = "acct_test_webhook";

    @BeforeEach
    void setUp() {
        service = new PaymentService(
                userRepository, bidRepository, announcementRepository,
                paymentRepository, auditService, eventPublisher,
                "whsec_test",
                PaymentServiceTestFactory.defaultConnectProperties());
        ReflectionTestUtils.setField(service, "commissionRate", new BigDecimal("0.12"));
    }

    private UserEntity buildUser(StripeAccountStatus status) {
        UserEntity u = new UserEntity();
        setId(u, userId);
        u.setFirebaseUid("uid-test");
        u.setStripeAccountId(ACCOUNT_ID);
        u.setStripeAccountStatus(status);
        return u;
    }

    private void setId(Object entity, UUID id) {
        try {
            Class<?> clazz = entity.getClass();
            Field f = null;
            while (clazz != null) {
                try { f = clazz.getDeclaredField("id"); break; }
                catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
            }
            if (f == null) throw new NoSuchFieldException("id not found");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Event buildAccountUpdatedEvent(boolean chargesEnabled, boolean payoutsEnabled,
                                            String disabledReason) {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        when(account.getChargesEnabled()).thenReturn(chargesEnabled);
        when(account.getPayoutsEnabled()).thenReturn(payoutsEnabled);

        if (disabledReason != null) {
            Account.Requirements requirements = mock(Account.Requirements.class);
            when(requirements.getDisabledReason()).thenReturn(disabledReason);
            when(account.getRequirements()).thenReturn(requirements);
        } else {
            when(account.getRequirements()).thenReturn(null);
        }

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(account));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("account.updated");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }

    @Test
    void chargesEnabled_and_payoutsEnabled_setsOnboardingComplete_and_publishesEvent() {
        UserEntity user = buildUser(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findByStripeAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.dispatchWebhookEvent(buildAccountUpdatedEvent(true, true, null));

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.ONBOARDING_COMPLETE);
        assertThat(user.getStripeOnboardingCompletedAt()).isNotNull();
        ArgumentCaptor<StripeOnboardingCompletedEvent> captor =
                ArgumentCaptor.forClass(StripeOnboardingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        verify(userRepository).save(user);
        verify(auditService).log(eq("USER"), eq(userId), eq("STRIPE_ONBOARDING_COMPLETE"), eq(userId), any());
    }

    @Test
    void chargesEnabled_twice_eventPublishedOnlyOnce_idempotencyGuard() {
        UserEntity user = buildUser(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findByStripeAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> {
            // Simulate the user being updated in DB after first call
            user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            return user;
        });

        // First call — should transition and emit event
        service.dispatchWebhookEvent(buildAccountUpdatedEvent(true, true, null));
        // Second call — user is now ONBOARDING_COMPLETE, no event emitted
        service.dispatchWebhookEvent(buildAccountUpdatedEvent(true, true, null));

        // Event published exactly once across both calls
        verify(eventPublisher, times(1)).publishEvent(any(StripeOnboardingCompletedEvent.class));
        verify(auditService, times(1)).log(any(), any(), eq("STRIPE_ONBOARDING_COMPLETE"), any(), any());
    }

    @Test
    void disabledReason_startsWithRejected_setsStatusRejected() {
        UserEntity user = buildUser(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findByStripeAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.dispatchWebhookEvent(buildAccountUpdatedEvent(false, false, "rejected.fraud"));

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.REJECTED);
        verify(eventPublisher, never()).publishEvent(any(StripeOnboardingCompletedEvent.class));
        verify(userRepository).save(user);
    }

    @Test
    void disabledReason_requirementsPastDue_setsStatusDisabled() {
        UserEntity user = buildUser(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findByStripeAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.dispatchWebhookEvent(buildAccountUpdatedEvent(false, false, "requirements.past_due"));

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.DISABLED);
        verify(eventPublisher, never()).publishEvent(any(StripeOnboardingCompletedEvent.class));
        verify(userRepository).save(user);
    }

    @Test
    void chargesDisabled_payoutsDisabled_noDisabledReason_noStateChange() {
        // Still pending — early return, no save, no event
        UserEntity user = buildUser(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findByStripeAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user));

        service.dispatchWebhookEvent(buildAccountUpdatedEvent(false, false, null));

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void unknownAccountId_doesNothing() {
        // Build a minimal event: only getId() is needed before the ifPresent guard exits early.
        Account account = mock(Account.class);
        lenient().when(account.getId()).thenReturn(ACCOUNT_ID);
        lenient().when(account.getChargesEnabled()).thenReturn(true);
        lenient().when(account.getPayoutsEnabled()).thenReturn(true);
        lenient().when(account.getRequirements()).thenReturn(null);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(account));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("account.updated");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);

        when(userRepository.findByStripeAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        service.dispatchWebhookEvent(event);

        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
