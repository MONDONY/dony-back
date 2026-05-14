package com.dony.api.payments;

import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.payments.exceptions.TravelerNotEligibleForPaymentException;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.ProcessedStripeEventRepository;
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
    @Mock ProcessedStripeEventRepository processedStripeEventRepository;

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
                "whsec_test",
                PaymentServiceTestFactory.defaultConnectProperties(),
                processedStripeEventRepository);
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

    private UserEntity buildTraveler(String stripeAccountId, boolean onboarded) {
        UserEntity t = buildUser(travelerId, "uid-traveler");
        t.setStripeAccountId(stripeAccountId);
        t.setStripeAccountStatus(onboarded ? StripeAccountStatus.ONBOARDING_COMPLETE : StripeAccountStatus.PENDING_ONBOARDING);
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
    void createEscrow_travelerNotOnboarded_throwsTravelerNotEligible() {
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

        Throwable thrown = catchThrowable(() -> service.createEscrow(req, "uid-sender"));
        assertThat(thrown).isInstanceOf(TravelerNotEligibleForPaymentException.class);
        assertThat(((TravelerNotEligibleForPaymentException) thrown).getTravelerId()).isEqualTo(travelerId);
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
        org.mockito.ArgumentCaptor<PaymentEntity> savedCaptor = org.mockito.ArgumentCaptor.forClass(PaymentEntity.class);
        when(paymentRepository.save(savedCaptor.capture())).thenAnswer(inv -> {
            PaymentEntity p = inv.getArgument(0);
            setId(p, UUID.randomUUID());
            return p;
        });
        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            Account mockAcct = mock(Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAcct.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> Account.retrieve(any(String.class))).thenReturn(mockAcct);

            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_test_new");
            when(mockPi.getClientSecret()).thenReturn("pi_secret");
            org.mockito.ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor =
                    org.mockito.ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
            piStatic.when(() -> PaymentIntent.create(paramsCaptor.capture())).thenReturn(mockPi);

            PaymentResponse resp = service.createEscrow(req, "uid-sender");

            assertThat(resp.getStatus()).isEqualTo("PENDING");
            assertThat(resp.getAmount()).isEqualByComparingTo(new BigDecimal("25.00"));
            verify(paymentRepository).save(any(PaymentEntity.class));

            // Separate charges and transfers model: NO application_fee_amount, NO transfer_data.
            PaymentIntentCreateParams params = paramsCaptor.getValue();
            assertThat(params.getApplicationFeeAmount()).isNull();
            assertThat(params.getTransferData()).isNull();
            assertThat(params.getCaptureMethod())
                    .isEqualTo(PaymentIntentCreateParams.CaptureMethod.MANUAL);
            assertThat(params.getOnBehalfOf()).isEqualTo("acct_traveler");
            assertThat(params.getStatementDescriptorSuffix()).isEqualTo("DONY");

            // Persisted payment is non-legacy.
            assertThat(savedCaptor.getValue().isLegacyDestinationCharge()).isFalse();
            // Commission still tracked on the entity for later Transfer (release at delivery).
            assertThat(savedCaptor.getValue().getCommissionAmount())
                    .isEqualByComparingTo(new BigDecimal("3.00"));
        }
    }

    @Test
    void createEscrow_travelerStripeAccountMissing_resetsAndThrows422() {
        UserEntity sender = buildUser(senderId, "uid-sender");
        BidEntity bid = buildBid(BidStatus.ACCEPTED);
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildTraveler("acct_stale_invalid", true);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StripeException missing = mock(StripeException.class);
        when(missing.getCode()).thenReturn("resource_missing");

        var req = mock(com.dony.api.payments.dto.CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            acctStatic.when(() -> Account.retrieve("acct_stale_invalid")).thenThrow(missing);

            assertDonyError(() -> service.createEscrow(req, "uid-sender"), "traveler-stripe-invalid");

            assertThat(traveler.getStripeAccountId()).isNull();
            assertThat(traveler.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.NOT_CREATED);
            verify(userRepository).save(traveler);
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
        user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(senderId)).thenReturn(Optional.of(user));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAccount = mock(Account.class);
            acctStatic.when(() -> Account.retrieve("acct_existing")).thenReturn(mockAccount);

            ConnectAccountResponse resp = service.createConnectAccount("uid-sender");

            assertThat(resp.stripeAccountId()).isEqualTo("acct_existing");
            assertThat(resp.stripeAccountStatus()).isEqualTo(StripeAccountStatus.ONBOARDING_COMPLETE);
        }
    }

    @Test
    void createConnectAccount_existingAccountMissingFromStripe_resetsAndRecreates() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_stale_invalid");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        user.setEmail("user@dony.app");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(senderId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StripeException missing = mock(StripeException.class);
        when(missing.getCode()).thenReturn("resource_missing");

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            acctStatic.when(() -> Account.retrieve("acct_stale_invalid")).thenThrow(missing);
            Account fresh = mock(Account.class);
            when(fresh.getId()).thenReturn("acct_fresh_456");
            acctStatic.when(() -> Account.create(any(AccountCreateParams.class))).thenReturn(fresh);

            ConnectAccountResponse resp = service.createConnectAccount("uid-sender");

            assertThat(resp.stripeAccountId()).isEqualTo("acct_fresh_456");
            assertThat(resp.stripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
            assertThat(user.getStripeAccountId()).isEqualTo("acct_fresh_456");
        }
    }

    @Test
    void createConnectAccount_success_createsAndSavesAccount() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId(null);
        user.setEmail("user@dony.app");
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(senderId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAccount = mock(Account.class);
            when(mockAccount.getId()).thenReturn("acct_new_123");
            acctStatic.when(() -> Account.create(any(AccountCreateParams.class))).thenReturn(mockAccount);

            ConnectAccountResponse resp = service.createConnectAccount("uid-sender");

            assertThat(resp.stripeAccountId()).isEqualTo("acct_new_123");
            assertThat(resp.stripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
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
        when(userRepository.findByIdForUpdate(senderId)).thenReturn(Optional.of(user));

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

    @Test
    void createOnboardingLink_accountMissingFromStripe_resetsAndThrowsConflict() {
        UserEntity user = buildUser(senderId, "uid-sender");
        user.setStripeAccountId("acct_stale_invalid");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StripeException missing = mock(StripeException.class);
        when(missing.getCode()).thenReturn("resource_missing");

        try (MockedStatic<AccountLink> alStatic = mockStatic(AccountLink.class)) {
            alStatic.when(() -> AccountLink.create(any(AccountLinkCreateParams.class)))
                    .thenThrow(missing);
            assertDonyError(() -> service.createOnboardingLink("uid-sender"), "stripe-account-invalid");
            assertThat(user.getStripeAccountId()).isNull();
            assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.NOT_CREATED);
            verify(userRepository).save(user);
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
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);

        Account mockAccount = mock(Account.class);
        when(mockAccount.getChargesEnabled()).thenReturn(true);
        when(mockAccount.getPayoutsEnabled()).thenReturn(true);
        when(mockAccount.getId()).thenReturn("acct_123");

        Event mockEvent = buildEventWith("account.updated", mockAccount);
        when(userRepository.findByStripeAccountId("acct_123")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.ONBOARDING_COMPLETE);
        assertThat(user.getStripeOnboardingCompletedAt()).isNotNull();
        verify(auditService).log(eq("USER"), any(), eq("STRIPE_ONBOARDING_COMPLETE"), any(), any());
        verify(eventPublisher).publishEvent(any(com.dony.api.payments.events.StripeOnboardingCompletedEvent.class));
    }

    @Test
    void handleWebhook_accountUpdated_chargesEnabled_alreadyOnboarded_idempotent() {
        // Already ONBOARDING_COMPLETE — status re-confirmed by Stripe, save is idempotent, no event emitted.
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

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        // No StripeOnboardingCompletedEvent emitted on idempotent re-confirmation
        verify(eventPublisher, never()).publishEvent(any(com.dony.api.payments.events.StripeOnboardingCompletedEvent.class));
        // save is called but no audit entry for STRIPE_ONBOARDING_COMPLETE
        verify(auditService, never()).log(any(), any(), eq("STRIPE_ONBOARDING_COMPLETE"), any(), any());
    }

    @Test
    void handleWebhook_accountUpdated_chargesDisabled_noDisabledReason_doesNothing() {
        // charges=false, payouts=false, no disabledReason → still pending, early return — no save, no event.
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

        try (MockedStatic<Webhook> wh = mockStatic(Webhook.class)) {
            wh.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenReturn(mockEvent);
            service.handleWebhook("payload", "sig");
        }

        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
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
        when(event.getType()).thenReturn(type);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of((com.stripe.model.StripeObject) stripeObj));
        return event;
    }
}
