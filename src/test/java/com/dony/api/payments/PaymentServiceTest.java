package com.dony.api.payments;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.payments.dto.ConnectAccountResponse;
import com.dony.api.payments.dto.OnboardingLinkResponse;
import com.dony.api.payments.dto.PaymentResponse;
import com.dony.api.payments.events.PaymentEscrowReadyEvent;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    PaymentService service;

    private final UUID senderId   = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId      = UUID.randomUUID();
    private final UUID annId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentService(
                userRepository, bidRepository, announcementRepository,
                paymentRepository, auditService, eventPublisher,
                "whsec_test");
        ReflectionTestUtils.setField(service, "commissionRate", new BigDecimal("0.12"));
        ReflectionTestUtils.setField(service, "returnUrl",  "https://dony.app/return");
        ReflectionTestUtils.setField(service, "refreshUrl", "https://dony.app/refresh");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void assertDonyError(ThrowableAssert.ThrowingCallable callable, String expectedErrorCode) {
        Throwable thrown = catchThrowable(callable);
        assertThat(thrown).isInstanceOf(DonyBusinessException.class);
        assertThat(((DonyBusinessException) thrown).getErrorCode()).isEqualTo(expectedErrorCode);
    }

    private UserEntity buildUser(UUID id, String firebaseUid) {
        UserEntity u = new UserEntity();
        setId(u, id);
        u.setFirebaseUid(firebaseUid);
        return u;
    }

    private BidEntity buildBid(BidStatus status) {
        BidEntity b = new BidEntity();
        setId(b, bidId);
        b.setAnnouncementId(annId);
        b.setSenderId(senderId);
        b.setWeightKg(BigDecimal.valueOf(5.0));
        b.setDeclaredValueEur(BigDecimal.valueOf(100.0));
        b.setStatus(status);
        return b;
    }

    private UserEntity buildTraveler(String stripeAccountId, boolean onboarded) {
        UserEntity t = buildUser(travelerId, "uid-traveler");
        t.setStripeAccountId(stripeAccountId);
        t.setStripeOnboarded(onboarded);
        return t;
    }

    private AnnouncementEntity buildAnnouncement() {
        AnnouncementEntity ann = new AnnouncementEntity();
        setId(ann, annId);
        ann.setTravelerId(travelerId);
        ann.setPricePerKg(BigDecimal.valueOf(5.0));
        return ann;
    }

    private PaymentEntity buildPayment(PaymentStatus status, String piId) {
        PaymentEntity p = new PaymentEntity();
        setId(p, UUID.randomUUID());
        p.setBidId(bidId);
        p.setStatus(status);
        p.setStripePaymentIntentId(piId);
        p.setAmount(new BigDecimal("25.00"));
        p.setCommissionAmount(new BigDecimal("3.00"));
        return p;
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

    // ── createEscrow validations ──────────────────────────────────────────────

    @Test
    void createEscrow_userNotFound_throwsNotFound() {
        when(userRepository.findByFirebaseUid("uid-xxx")).thenReturn(Optional.empty());
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        assertDonyError(() -> service.createEscrow(req, "uid-xxx"), "user-not-found");
    }

    @Test
    void createEscrow_bidNotFound_throwsNotFound() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);
        assertDonyError(() -> service.createEscrow(req, "uid-sender"), "bid-not-found");
    }

    @Test
    void createEscrow_notSender_throwsForbidden() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        bid.setSenderId(UUID.randomUUID()); // different sender
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);
        assertDonyError(() -> service.createEscrow(req, "uid-sender"), "forbidden");
    }

    @Test
    void createEscrow_bidNotAccepted_throwsUnprocessable() {
        // PENDING and ACCEPTED bids are now both allowed (pay-before-accept flow).
        // REJECTED and CANCELLED bids must still be rejected.
        UserEntity sender = buildUser(senderId, "uid-sender");
        BidEntity bid = buildBid(BidStatus.REJECTED);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);
        assertDonyError(() -> service.createEscrow(req, "uid-sender"), "bid-not-payable");
    }

    @Test
    void createEscrow_paymentAlreadyInEscrow_throwsConflict() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        PaymentEntity existing = buildPayment(PaymentStatus.ESCROW, "pi_existing");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(existing));
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);
        assertDonyError(() -> service.createEscrow(req, "uid-sender"), "payment-already-completed");
    }

    @Test
    void createEscrow_existingPendingPayment_requiresPaymentMethod_returnsExisting() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        PaymentEntity existing = buildPayment(PaymentStatus.PENDING, "pi_existing");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(existing));
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getStatus()).thenReturn("requires_payment_method");
            when(mockPi.getClientSecret()).thenReturn("client_secret_existing");
            piStatic.when(() -> PaymentIntent.retrieve(anyString())).thenReturn(mockPi);

            PaymentResponse resp = service.createEscrow(req, "uid-sender");

            assertThat(resp.getStatus()).isEqualTo("PENDING");
        }
    }

    @Test
    void createEscrow_existingPendingPayment_stripeAlreadyAuthorized_throwsConflict() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        PaymentEntity existing = buildPayment(PaymentStatus.PENDING, "pi_authorized");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(existing));
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getStatus()).thenReturn("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(anyString())).thenReturn(mockPi);

            assertDonyError(() -> service.createEscrow(req, "uid-sender"), "payment-already-completed");
        }
    }

    @Test
    void createEscrow_travelerNotOnboarded_throwsUnprocessable() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildTraveler(null, false);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);
        assertDonyError(() -> service.createEscrow(req, "uid-sender"), "traveler-not-onboarded");
    }

    @Test
    void createEscrow_success_createsPaymentIntent() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildTraveler("acct_traveler", true);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentEntity p = inv.getArgument(0);
            setId(p, UUID.randomUUID());
            return p;
        });
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_test_new");
            when(mockPi.getClientSecret()).thenReturn("pi_secret");
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(mockPi);

            PaymentResponse resp = service.createEscrow(req, "uid-sender");

            assertThat(resp.getStatus()).isEqualTo("PENDING");
            assertThat(resp.getAmount()).isEqualByComparingTo(new BigDecimal("25.00"));
            verify(paymentRepository).save(any(PaymentEntity.class));
        }
    }

    @Test
    void createEscrow_stripeException_throwsInternalError() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildTraveler("acct_traveler", true);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(mock(StripeException.class));
            assertDonyError(() -> service.createEscrow(req, "uid-sender"), "payment-creation-failed");
        }
    }

    // ── createConnectAccount ──────────────────────────────────────────────────

    @Test
    void createConnectAccount_alreadyHasStripeId_returnsExisting() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_existing");
        user.setStripeOnboarded(true);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));

        ConnectAccountResponse resp = service.createConnectAccount("uid-sender");

        assertThat(resp.stripeAccountId()).isEqualTo("acct_existing");
        assertThat(resp.stripeOnboarded()).isTrue();
    }

    @Test
    void createConnectAccount_success_createsAndSavesAccount() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId(null);
        user.setEmail("user@dony.app");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAccount = mock(Account.class);
            when(mockAccount.getId()).thenReturn("acct_new_123");
            acctStatic.when(() -> Account.create(any(AccountCreateParams.class))).thenReturn(mockAccount);

            ConnectAccountResponse resp = service.createConnectAccount("uid-sender");

            assertThat(resp.stripeAccountId()).isEqualTo("acct_new_123");
            assertThat(resp.stripeOnboarded()).isFalse();
            verify(userRepository).save(user);
            verify(auditService).log(eq("USER"), any(), eq("STRIPE_ACCOUNT_CREATED"), any(), any());
        }
    }

    @Test
    void createConnectAccount_stripeError_throwsInternalError() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId(null);
        user.setEmail("user@dony.app");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            acctStatic.when(() -> Account.create(any(AccountCreateParams.class)))
                    .thenThrow(new RuntimeException("Stripe down"));
            assertDonyError(() -> service.createConnectAccount("uid-sender"), "stripe-account-creation-failed");
        }
    }

    // ── createOnboardingLink ──────────────────────────────────────────────────

    @Test
    void createOnboardingLink_noStripeAccount_throwsConflict() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId(null);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        assertDonyError(() -> service.createOnboardingLink("uid-sender"), "stripe-account-required");
    }

    @Test
    void createOnboardingLink_success_returnsUrl() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_existing");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));

        try (MockedStatic<AccountLink> alStatic = mockStatic(AccountLink.class)) {
            AccountLink mockLink = mock(AccountLink.class);
            when(mockLink.getUrl()).thenReturn("https://connect.stripe.com/start");
            alStatic.when(() -> AccountLink.create(any(AccountLinkCreateParams.class))).thenReturn(mockLink);

            OnboardingLinkResponse resp = service.createOnboardingLink("uid-sender");

            assertThat(resp.url()).isEqualTo("https://connect.stripe.com/start");
        }
    }

    @Test
    void createOnboardingLink_stripeError_throwsInternalError() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_existing");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));

        try (MockedStatic<AccountLink> alStatic = mockStatic(AccountLink.class)) {
            alStatic.when(() -> AccountLink.create(any(AccountLinkCreateParams.class)))
                    .thenThrow(new RuntimeException("Stripe error"));
            assertDonyError(() -> service.createOnboardingLink("uid-sender"), "stripe-link-creation-failed");
        }
    }

    // ── handleWebhook ─────────────────────────────────────────────────────────

    @Test
    void handleWebhook_invalidSignature_throwsBadRequest() {
        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenThrow(mock(SignatureVerificationException.class));
            assertDonyError(() -> service.handleWebhook("payload", "bad-sig"), "invalid-webhook-signature");
        }
    }

    @Test
    void handleWebhook_unhandledEventType_doesNothing() {
        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn("some.unknown.event");

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void handleWebhook_accountUpdated_chargesEnabled_setsOnboarded() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_123");
        user.setStripeOnboarded(false);

        Account mockAccount = mock(Account.class);
        when(mockAccount.getChargesEnabled()).thenReturn(true);
        when(mockAccount.getId()).thenReturn("acct_123");

        Event mockEvent = buildEventWith("account.updated", mockAccount);
        when(userRepository.findByStripeAccountId("acct_123")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        assertThat(user.isStripeOnboarded()).isTrue();
        verify(auditService).log(eq("USER"), any(), eq("STRIPE_ONBOARDING_COMPLETE"), any(), any());
    }

    @Test
    void handleWebhook_accountUpdated_chargesEnabled_alreadyOnboarded_skips() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_123");
        user.setStripeOnboarded(true); // already onboarded

        Account mockAccount = mock(Account.class);
        when(mockAccount.getChargesEnabled()).thenReturn(true);
        when(mockAccount.getId()).thenReturn("acct_123");

        Event mockEvent = buildEventWith("account.updated", mockAccount);
        when(userRepository.findByStripeAccountId("acct_123")).thenReturn(Optional.of(user));

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        verify(userRepository, never()).save(any());
    }

    @Test
    void handleWebhook_accountUpdated_chargesDisabled_doesNothing() {
        Account mockAccount = mock(Account.class);
        when(mockAccount.getChargesEnabled()).thenReturn(false);

        Event mockEvent = buildEventWith("account.updated", mockAccount);

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }
        verifyNoInteractions(userRepository);
    }

    @Test
    void handleWebhook_paymentEscrowActive_pendingPayment_movesToEscrow() {
        PaymentEntity payment = buildPayment(PaymentStatus.PENDING, "pi_escrow");

        PaymentIntent mockPi = mock(PaymentIntent.class);
        when(mockPi.getId()).thenReturn("pi_escrow");
        when(mockPi.getAmountCapturable()).thenReturn(2500L);

        Event mockEvent = buildEventWith("payment_intent.amount_capturable_updated", mockPi);
        when(paymentRepository.findByStripePaymentIntentId("pi_escrow")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        verify(eventPublisher).publishEvent(any(PaymentEscrowReadyEvent.class));
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_ESCROW_ACTIVE"), any(), any());
    }

    @Test
    void handleWebhook_paymentEscrowActive_nonPendingPayment_doesNothing() {
        PaymentEntity payment = buildPayment(PaymentStatus.ESCROW, "pi_already_escrow");

        PaymentIntent mockPi = mock(PaymentIntent.class);
        when(mockPi.getId()).thenReturn("pi_already_escrow");

        Event mockEvent = buildEventWith("payment_intent.amount_capturable_updated", mockPi);
        when(paymentRepository.findByStripePaymentIntentId("pi_already_escrow")).thenReturn(Optional.of(payment));

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW); // unchanged
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void handleWebhook_paymentFailed_pendingPayment_marksAsFailed() {
        PaymentEntity payment = buildPayment(PaymentStatus.PENDING, "pi_failed");

        PaymentIntent mockPi = mock(PaymentIntent.class);
        when(mockPi.getId()).thenReturn("pi_failed");

        Event mockEvent = buildEventWith("payment_intent.payment_failed", mockPi);
        when(paymentRepository.findByStripePaymentIntentId("pi_failed")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_FAILED"), any(), any());
    }

    @Test
    void handleWebhook_chargeRefunded_marksAsRefunded() {
        PaymentEntity payment = buildPayment(PaymentStatus.ESCROW, "pi_refund");

        Charge mockCharge = mock(Charge.class);
        when(mockCharge.getPaymentIntent()).thenReturn("pi_refund");

        Event mockEvent = buildEventWith("charge.refunded", mockCharge);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED"), any(), any());
    }

    @Test
    void handleWebhook_chargeRefunded_alreadyRefunded_idempotent() {
        PaymentEntity payment = buildPayment(PaymentStatus.REFUNDED, "pi_already_refunded");

        Charge mockCharge = mock(Charge.class);
        when(mockCharge.getPaymentIntent()).thenReturn("pi_already_refunded");

        Event mockEvent = buildEventWith("charge.refunded", mockCharge);
        when(paymentRepository.findByStripePaymentIntentId("pi_already_refunded")).thenReturn(Optional.of(payment));

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED); // unchanged
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void handleWebhook_chargeRefunded_noPaymentIntentId_ignores() {
        Charge mockCharge = mock(Charge.class);
        when(mockCharge.getPaymentIntent()).thenReturn(null);

        Event mockEvent = buildEventWith("charge.refunded", mockCharge);

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        verifyNoInteractions(paymentRepository);
    }

    // ── getPaymentStatusForBid ────────────────────────────────────────────────

    @Test
    void getPaymentStatusForBid_noPayment_returnsEmpty() {
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        assertThat(service.getPaymentStatusForBid(bidId)).isEmpty();
    }

    @Test
    void getPaymentStatusForBid_withPayment_returnsResponse() {
        PaymentEntity payment = buildPayment(PaymentStatus.ESCROW, null);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        Optional<PaymentResponse> resp = service.getPaymentStatusForBid(bidId);

        assertThat(resp).isPresent();
        assertThat(resp.get().getStatus()).isEqualTo("ESCROW");
        assertThat(resp.get().getAmount()).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    // ── Helper to build a mocked Stripe Event with deserialized object ─────────

    private Event buildEventWith(String type, Object stripeObj) {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn(type);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of((com.stripe.model.StripeObject) stripeObj));
        return event;
    }
}
