package com.dony.api.payments;

import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.payments.exceptions.TravelerNotEligibleForPaymentException;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.config.StripeConnectProperties;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.payments.dto.ConnectAccountResponse;
import com.dony.api.payments.dto.OnboardingLinkResponse;
import com.dony.api.payments.dto.PaymentResponse;
import com.dony.api.payments.events.PaymentEscrowReadyEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
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
                PaymentServiceTestFactory.defaultConnectProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                org.mockito.Mockito.mock(com.dony.api.common.stripe.AdminAlertService.class));
        ReflectionTestUtils.setField(service, "commissionRate", new BigDecimal("0.12"));
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
        p.setStripePaymentIntentId(piId);
        p.setStatus(status);
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
            if (f == null) throw new NoSuchFieldException("id not found in class hierarchy");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException("Could not set id via reflection", e);
        }
    }

    // ── createEscrow ──────────────────────────────────────────────────────────

    @Test
    void createEscrow_bidNotFound_throwsNotFound() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        var request = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);

        assertDonyError(() -> service.createEscrow(request, "uid-sender"), "bid-not-found");
    }

    @Test
    void createEscrow_bidNotBelongingToSender_throwsForbidden() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        bid.setSenderId(UUID.randomUUID()); // different sender
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        var request = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);

        assertDonyError(() -> service.createEscrow(request, "uid-sender"), "forbidden");
    }

    @Test
    void createEscrow_rejectedBid_throwsUnprocessable() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        BidEntity bid = buildBid(BidStatus.REJECTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        var request = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);

        assertDonyError(() -> service.createEscrow(request, "uid-sender"), "bid-not-payable");
    }

    @Test
    void createEscrow_alreadyInEscrow_throwsConflict() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        PaymentEntity existing = buildPayment(PaymentStatus.ESCROW, "pi_existing");
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(existing));

        var request = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);

        assertDonyError(() -> service.createEscrow(request, "uid-sender"), "payment-already-completed");
    }

    @Test
    void createEscrow_travelerNotOnboarded_throwsTravelerNotEligible() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        AnnouncementEntity ann = buildAnnouncement();
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        var request = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);

        Throwable thrown = catchThrowable(() -> service.createEscrow(request, "uid-sender"));
        assertThat(thrown).isInstanceOf(TravelerNotEligibleForPaymentException.class);
    }

    // ── createConnectAccount ──────────────────────────────────────────────────

    @Test
    void createConnectAccount_userNotFound_throwsNotFound() {
        when(userRepository.findByFirebaseUid("uid-x")).thenReturn(Optional.empty());
        assertDonyError(() -> service.createConnectAccount("uid-x"), "user-not-found");
    }

    @Test
    void createConnectAccount_alreadyHasAccount_returnsExisting() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_existing");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(senderId)).thenReturn(Optional.of(user));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAcct = mock(Account.class);
            acctStatic.when(() -> Account.retrieve("acct_existing")).thenReturn(mockAcct);

            ConnectAccountResponse resp = service.createConnectAccount("uid-sender");
            assertThat(resp.stripeAccountId()).isEqualTo("acct_existing");
        }
    }

    @Test
    void createConnectAccount_createsNewAccount_setsStatusPending() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setEmail("test@dony.app");
        user.setCountry("FR");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(senderId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAcct = mock(Account.class);
            when(mockAcct.getId()).thenReturn("acct_new");
            acctStatic.when(() -> Account.create(any(AccountCreateParams.class))).thenReturn(mockAcct);

            ConnectAccountResponse resp = service.createConnectAccount("uid-sender");
            assertThat(resp.stripeAccountId()).isEqualTo("acct_new");
            assertThat(resp.stripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
        }
    }

    // ── createOnboardingLink ──────────────────────────────────────────────────

    @Test
    void createOnboardingLink_noStripeAccount_throwsConflict() {
        UserEntity user = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        assertDonyError(() -> service.createOnboardingLink("uid-sender"), "stripe-account-required");
    }

    @Test
    void createOnboardingLink_success_returnsUrl() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_123");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));

        try (MockedStatic<AccountLink> linkStatic = mockStatic(AccountLink.class)) {
            AccountLink link = mock(AccountLink.class);
            when(link.getUrl()).thenReturn("https://connect.stripe.com/onboarding");
            linkStatic.when(() -> AccountLink.create(any(AccountLinkCreateParams.class))).thenReturn(link);

            OnboardingLinkResponse resp = service.createOnboardingLink("uid-sender");
            assertThat(resp.url()).isEqualTo("https://connect.stripe.com/onboarding");
        }
    }

    @Test
    void createOnboardingLink_stripeAccountMissing_resetsAndThrowsConflict() throws Exception {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_deleted");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));

        try (MockedStatic<AccountLink> linkStatic = mockStatic(AccountLink.class)) {
            com.stripe.exception.InvalidRequestException missing =
                    new com.stripe.exception.InvalidRequestException("No such account",
                            "acct_deleted", null, "resource_missing", 404, null);
            linkStatic.when(() -> AccountLink.create(any(AccountLinkCreateParams.class)))
                    .thenThrow(missing);
            assertDonyError(() -> service.createOnboardingLink("uid-sender"), "stripe-account-invalid");
            assertThat(user.getStripeAccountId()).isNull();
            assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.NOT_CREATED);
            verify(userRepository).save(user);
        }
    }

    // ── Webhook handlers (package-private, called directly) ──────────────────

    @Test
    void handleAccountUpdated_chargesEnabled_setsOnboarded() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_123");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);

        Account mockAccount = mock(Account.class);
        when(mockAccount.getChargesEnabled()).thenReturn(true);
        when(mockAccount.getPayoutsEnabled()).thenReturn(true);
        when(mockAccount.getId()).thenReturn("acct_123");

        Event mockEvent = buildEventWith("account.updated", mockAccount);
        when(userRepository.findByStripeAccountId("acct_123")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleAccountUpdated(mockEvent);

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.ONBOARDING_COMPLETE);
        assertThat(user.getStripeOnboardingCompletedAt()).isNotNull();
        verify(auditService).log(eq("USER"), any(), eq("STRIPE_ONBOARDING_COMPLETE"), any(), any());
        verify(eventPublisher).publishEvent(any(com.dony.api.payments.events.StripeOnboardingCompletedEvent.class));
    }

    @Test
    void handleAccountUpdated_chargesEnabled_alreadyOnboarded_idempotent() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_123");
        user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

        Account mockAccount = mock(Account.class);
        when(mockAccount.getChargesEnabled()).thenReturn(true);
        when(mockAccount.getPayoutsEnabled()).thenReturn(true);
        when(mockAccount.getId()).thenReturn("acct_123");

        Event mockEvent = buildEventWith("account.updated", mockAccount);
        when(userRepository.findByStripeAccountId("acct_123")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleAccountUpdated(mockEvent);

        verify(eventPublisher, never()).publishEvent(any(com.dony.api.payments.events.StripeOnboardingCompletedEvent.class));
        verify(auditService, never()).log(any(), any(), eq("STRIPE_ONBOARDING_COMPLETE"), any(), any());
    }

    @Test
    void handleAccountUpdated_chargesDisabled_noDisabledReason_doesNothing() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_pending");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);

        Account mockAccount = mock(Account.class);
        when(mockAccount.getChargesEnabled()).thenReturn(false);
        when(mockAccount.getPayoutsEnabled()).thenReturn(false);
        when(mockAccount.getId()).thenReturn("acct_pending");
        when(mockAccount.getRequirements()).thenReturn(null);

        Event mockEvent = buildEventWith("account.updated", mockAccount);
        when(userRepository.findByStripeAccountId("acct_pending")).thenReturn(Optional.of(user));

        service.handleAccountUpdated(mockEvent);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void handlePaymentEscrowActive_pendingPayment_movesToEscrow() {
        PaymentEntity payment = buildPayment(PaymentStatus.PENDING, "pi_escrow");

        PaymentIntent mockPi = mock(PaymentIntent.class);
        when(mockPi.getId()).thenReturn("pi_escrow");
        when(mockPi.getAmountCapturable()).thenReturn(2500L);

        Event mockEvent = buildEventWith("payment_intent.amount_capturable_updated", mockPi);
        when(paymentRepository.findByStripePaymentIntentId("pi_escrow")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handlePaymentEscrowActive(mockEvent);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        verify(eventPublisher).publishEvent(any(PaymentEscrowReadyEvent.class));
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_ESCROW_ACTIVE"), any(), any());
    }

    @Test
    void handlePaymentEscrowActive_nonPendingPayment_doesNothing() {
        PaymentEntity payment = buildPayment(PaymentStatus.ESCROW, "pi_already_escrow");

        PaymentIntent mockPi = mock(PaymentIntent.class);
        when(mockPi.getId()).thenReturn("pi_already_escrow");

        Event mockEvent = buildEventWith("payment_intent.amount_capturable_updated", mockPi);
        when(paymentRepository.findByStripePaymentIntentId("pi_already_escrow")).thenReturn(Optional.of(payment));

        service.handlePaymentEscrowActive(mockEvent);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW); // unchanged
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void handlePaymentFailed_pendingPayment_marksAsFailed() {
        PaymentEntity payment = buildPayment(PaymentStatus.PENDING, "pi_failed");

        PaymentIntent mockPi = mock(PaymentIntent.class);
        when(mockPi.getId()).thenReturn("pi_failed");

        Event mockEvent = buildEventWith("payment_intent.payment_failed", mockPi);
        when(paymentRepository.findByStripePaymentIntentId("pi_failed")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handlePaymentFailed(mockEvent);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_FAILED"), any(), any());
    }

    @Test
    void handleChargeRefunded_marksAsRefunded() {
        PaymentEntity payment = buildPayment(PaymentStatus.ESCROW, "pi_refund");

        Charge mockCharge = mock(Charge.class);
        when(mockCharge.getPaymentIntent()).thenReturn("pi_refund");

        Event mockEvent = buildEventWith("charge.refunded", mockCharge);
        when(paymentRepository.findByStripePaymentIntentId("pi_refund")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleChargeRefunded(mockEvent);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED"), any(), any());
    }

    @Test
    void handleChargeRefunded_alreadyRefunded_idempotent() {
        PaymentEntity payment = buildPayment(PaymentStatus.REFUNDED, "pi_already_refunded");

        Charge mockCharge = mock(Charge.class);
        when(mockCharge.getPaymentIntent()).thenReturn("pi_already_refunded");

        Event mockEvent = buildEventWith("charge.refunded", mockCharge);
        when(paymentRepository.findByStripePaymentIntentId("pi_already_refunded")).thenReturn(Optional.of(payment));

        service.handleChargeRefunded(mockEvent);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED); // unchanged
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void handleChargeRefunded_noPaymentIntentId_ignores() {
        Charge mockCharge = mock(Charge.class);
        when(mockCharge.getPaymentIntent()).thenReturn(null);

        Event mockEvent = buildEventWith("charge.refunded", mockCharge);

        service.handleChargeRefunded(mockEvent);

        verifyNoInteractions(paymentRepository);
    }

    // ── getPaymentStatusForBid ────────────────────────────────────────────────

    @Test
    void getPaymentStatusForBid_noPayment_returnsEmpty() {
        UserEntity caller = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(caller));
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        AnnouncementEntity ann = buildAnnouncement();
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        assertThat(service.getPaymentStatusForBid(bidId, "uid-sender")).isEmpty();
    }

    @Test
    void getPaymentStatusForBid_withPayment_returnsResponse() {
        UserEntity caller = buildUser(senderId, "uid-sender");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(caller));
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        AnnouncementEntity ann = buildAnnouncement();
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        PaymentEntity payment = buildPayment(PaymentStatus.ESCROW, null);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        Optional<PaymentResponse> resp = service.getPaymentStatusForBid(bidId, "uid-sender");

        assertThat(resp).isPresent();
        assertThat(resp.get().getStatus()).isEqualTo("ESCROW");
        assertThat(resp.get().getAmount()).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    // ── confirmBidPayment ─────────────────────────────────────────────────────

    @Test
    void confirmBidPayment_promotes_when_PI_requires_capture() {
        BidEntity bid = buildBid(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentIntentId("pi_test");
        AnnouncementEntity ann = buildAnnouncement();
        ann.setDepartureCity("Paris");
        ann.setArrivalCity("Dakar");
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setFirstName("Marie");

        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(bidRepository.findByPaymentIntentId("pi_test")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            piStatic.when(() -> PaymentIntent.retrieve("pi_test")).thenReturn(pi);

            boolean result = service.confirmBidPayment(bidId);

            assertThat(result).isTrue();
            assertThat(bid.getStatus()).isEqualTo(BidStatus.PAYMENT_ESCROWED);
            verify(eventPublisher).publishEvent(any(com.dony.api.matching.events.BidCreatedEvent.class));
        }
    }

    @Test
    void confirmBidPayment_idempotent_when_already_PAYMENT_ESCROWED() {
        BidEntity bid = buildBid(BidStatus.PAYMENT_ESCROWED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        boolean result = service.confirmBidPayment(bidId);

        assertThat(result).isTrue();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void confirmBidPayment_returns_false_when_bid_in_other_status() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        boolean result = service.confirmBidPayment(bidId);

        assertThat(result).isFalse();
    }

    @Test
    void confirmBidPayment_returns_false_when_PI_status_unknown() {
        BidEntity bid = buildBid(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentIntentId("pi_test");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_payment_method");
            piStatic.when(() -> PaymentIntent.retrieve("pi_test")).thenReturn(pi);

            boolean result = service.confirmBidPayment(bidId);

            assertThat(result).isFalse();
            assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        }
    }

    @Test
    void confirmBidPayment_throws_when_bid_not_found() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        assertDonyError(() -> service.confirmBidPayment(bidId), "bid-not-found");
    }

    @Test
    void confirmBidPayment_returns_false_when_no_payment_intent_id() {
        BidEntity bid = buildBid(BidStatus.AWAITING_PAYMENT);
        // paymentIntentId left null
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        boolean result = service.confirmBidPayment(bidId);

        assertThat(result).isFalse();
    }

    @Test
    void confirmBidPayment_throws_502_when_stripe_fails() throws StripeException {
        BidEntity bid = buildBid(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentIntentId("pi_test");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            StripeException ex = mock(StripeException.class);
            when(ex.getMessage()).thenReturn("network down");
            piStatic.when(() -> PaymentIntent.retrieve("pi_test")).thenThrow(ex);

            assertDonyError(() -> service.confirmBidPayment(bidId), "stripe-error");
        }
    }

    // ── Helper to build a mocked Stripe Event with deserialized object ─────────

    private Event buildEventWith(String type, Object stripeObj) {
        Event event = mock(Event.class);
        lenient().when(event.getType()).thenReturn(type);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of((com.stripe.model.StripeObject) stripeObj));
        return event;
    }
}
