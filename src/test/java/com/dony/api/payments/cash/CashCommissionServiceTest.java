package com.dony.api.payments.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.CommissionRateResolver;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.payments.cash.dto.AcceptBidResponse;
import com.dony.api.payments.cash.dto.AcceptanceStatusDto;
import com.dony.api.payments.cash.dto.CommissionMethodResponse;
import com.dony.api.payments.cash.dto.ConfirmAcceptanceResponse;
import com.dony.api.payments.cash.dto.SetupCommissionMethodResponse;
import com.dony.api.payments.cash.event.CommissionMethodDetachedEvent;
import com.dony.api.payments.cash.exception.CommissionMethodMissingException;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class CashCommissionServiceTest {

    @Mock private UserRepository userRepo;
    @Mock private BidRepository bidRepo;
    @Mock private AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher events;
    @Mock private com.dony.api.payments.wallet.WalletService walletService;
    @Mock private com.dony.api.payments.wallet.WalletTransactionRepository walletTransactionRepository;
    @Mock private com.dony.api.common.AuditService auditService;
    @Mock private CommissionRateResolver commissionRateResolver;

    private final CommissionProperties props =
            new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24);

    private CashCommissionService service;

    @BeforeEach
    void setUp() {
        lenient().when(commissionRateResolver.resolve(any(), any())).thenReturn(new BigDecimal("0.12"));
        lenient().when(commissionRateResolver.resolve(any())).thenReturn(new BigDecimal("0.12"));
        service = new CashCommissionService(props, userRepo, bidRepo, announcementRepo, events,
                walletService, walletTransactionRepository, auditService, commissionRateResolver);
        service.setClock(Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC));
    }

    // --- helpers ---

    private UserEntity userWithCard(String brand, String last4, int expMonth, int expYear) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setStripeCustomerId("cus_test");
        u.setCommissionPaymentMethodId("pm_test");
        u.setCommissionCardBrand(brand);
        u.setCommissionCardLast4(last4);
        u.setCommissionCardExpMonth(expMonth);
        u.setCommissionCardExpYear(expYear);
        return u;
    }

    private BidEntity bidWithDeclaredValue(BigDecimal value, UUID travelerId) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setDeclaredValueEur(value);
        b.setWeightKg(new BigDecimal("5"));
        b.setAnnouncementId(UUID.randomUUID());
        b.setPaymentMethod(com.dony.api.payments.cash.PaymentMethod.CASH);
        return b;
    }

    private AnnouncementEntity announcementWithPrice(UUID id, BigDecimal pricePerKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", id);
        a.setPricePerKg(pricePerKg);
        a.setAvailableKg(new BigDecimal("20"));
        return a;
    }

    // ===================== computeCommission =====================

    @Nested
    class ComputeCommission {

        @Test
        void usesRateAbovePlancher() {
            assertThat(service.computeCommission(new BigDecimal("100")))
                    .isEqualByComparingTo(new BigDecimal("12.00"));
        }

        @Test
        void appliesPlancherForSmallValue() {
            assertThat(service.computeCommission(new BigDecimal("5")))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
        }

        @Test
        void roundsHalfUp() {
            // 12% of 9.00 = 1.08
            assertThat(service.computeCommission(new BigDecimal("9.00")))
                    .isEqualByComparingTo(new BigDecimal("1.08"));
        }

        @Test
        void plancherAppliesToSubUnitValues() {
            // 12% of 8.34 = 1.0008 → rounded to 1.00, plancher kicks in
            assertThat(service.computeCommission(new BigDecimal("8.34")))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
        }

        @Test
        void computeBidCommission_appliesUserOverride_andSnapshotsRateOnBid() {
            UUID travelerId = UUID.randomUUID();
            UUID senderId = UUID.randomUUID();
            // Override 8 % pour ce couple (écrase le stub générique 12 % du setUp).
            when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.08"));

            BidEntity bid = new BidEntity();
            ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
            bid.setSenderId(senderId);
            bid.setWeightKg(new BigDecimal("10"));
            AnnouncementEntity ann = new AnnouncementEntity();
            ReflectionTestUtils.setField(ann, "id", UUID.randomUUID());
            ann.setTravelerId(travelerId);
            ann.setPricePerKg(new BigDecimal("20"));

            // cashAmount = 10 × 20 = 200 → 200 × 8 % = 16,00 (au lieu de 24,00 au taux global)
            BigDecimal commission = service.computeBidCommission(bid, ann);

            assertThat(commission).isEqualByComparingTo("16.00");
            assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.08");
        }
    }

    // ===================== setupCommissionMethod =====================

    @Nested
    class SetupCommissionMethod {

        @Test
        void createsSetupIntentForExistingCustomer() throws StripeException {
            UUID userId = UUID.randomUUID();
            UserEntity user = new UserEntity();
            ReflectionTestUtils.setField(user, "id", userId);
            user.setStripeCustomerId("cus_existing");
            when(userRepo.findById(userId)).thenReturn(Optional.of(user));

            SetupIntent mockIntent = new SetupIntent();
            mockIntent.setClientSecret("seti_secret");

            try (MockedStatic<SetupIntent> si = mockStatic(SetupIntent.class)) {
                si.when(() -> SetupIntent.create(any(SetupIntentCreateParams.class))).thenReturn(mockIntent);

                SetupCommissionMethodResponse resp = service.setupCommissionMethod(userId);
                assertThat(resp.clientSecret()).isEqualTo("seti_secret");

                ArgumentCaptor<SetupIntentCreateParams> captor = ArgumentCaptor.forClass(SetupIntentCreateParams.class);
                si.verify(() -> SetupIntent.create(captor.capture()));
                assertThat(captor.getValue().getCustomer()).isEqualTo("cus_existing");
                assertThat(captor.getValue().getUsage()).isEqualTo(SetupIntentCreateParams.Usage.OFF_SESSION);
            }
        }

        @Test
        void createsStripeCustomerIfMissing() throws StripeException {
            UUID userId = UUID.randomUUID();
            UserEntity user = new UserEntity();
            ReflectionTestUtils.setField(user, "id", userId);
            user.setEmail("test@example.com");
            when(userRepo.findById(userId)).thenReturn(Optional.of(user));

            Customer mockCustomer = new Customer();
            mockCustomer.setId("cus_new");
            SetupIntent mockIntent = new SetupIntent();
            mockIntent.setClientSecret("seti_new");

            try (MockedStatic<Customer> c = mockStatic(Customer.class);
                 MockedStatic<SetupIntent> si = mockStatic(SetupIntent.class)) {
                c.when(() -> Customer.create(any(CustomerCreateParams.class))).thenReturn(mockCustomer);
                si.when(() -> SetupIntent.create(any(SetupIntentCreateParams.class))).thenReturn(mockIntent);

                service.setupCommissionMethod(userId);

                assertThat(user.getStripeCustomerId()).isEqualTo("cus_new");
                verify(userRepo, atLeastOnce()).save(user);
            }
        }
    }

    // ===================== getCommissionMethod =====================

    @Nested
    class GetCommissionMethod {

        @Test
        void returnsNullWhenNotConfigured() {
            UUID userId = UUID.randomUUID();
            UserEntity user = new UserEntity();
            ReflectionTestUtils.setField(user, "id", userId);
            when(userRepo.findById(userId)).thenReturn(Optional.of(user));
            assertThat(service.getCommissionMethod(userId)).isNull();
        }

        @Test
        void returnsValidStatusForFutureExpiry() {
            UUID userId = UUID.randomUUID();
            // Clock fixed to 2026-06, card expires 2028-12
            UserEntity user = userWithCard("visa", "4242", 12, 2028);
            when(userRepo.findById(userId)).thenReturn(Optional.of(user));

            CommissionMethodResponse resp = service.getCommissionMethod(userId);
            assertThat(resp.expirationStatus()).isEqualTo(ExpirationStatus.VALID);
        }

        @Test
        void returnsExpiresSoonWhenWithinOneMonth() {
            UUID userId = UUID.randomUUID();
            // Clock fixed to 2026-06-01, card expires 2026-07 (1 month away)
            UserEntity user = userWithCard("visa", "4242", 7, 2026);
            when(userRepo.findById(userId)).thenReturn(Optional.of(user));

            CommissionMethodResponse resp = service.getCommissionMethod(userId);
            assertThat(resp.expirationStatus()).isEqualTo(ExpirationStatus.EXPIRES_SOON);
        }

        @Test
        void returnsExpiredWhenPastMonth() {
            UUID userId = UUID.randomUUID();
            // Clock fixed to 2026-06-01, card expired 2026-05
            UserEntity user = userWithCard("visa", "4242", 5, 2026);
            when(userRepo.findById(userId)).thenReturn(Optional.of(user));

            CommissionMethodResponse resp = service.getCommissionMethod(userId);
            assertThat(resp.expirationStatus()).isEqualTo(ExpirationStatus.EXPIRED);
        }
    }

    // ===================== detachCommissionMethod =====================

    @Nested
    class DetachCommissionMethod {

        @Test
        void detachesAndClearsUserColumns() throws StripeException {
            UUID userId = UUID.randomUUID();
            UserEntity user = userWithCard("visa", "4242", 12, 2028);
            user.setCommissionPaymentMethodId("pm_abc");
            when(userRepo.findById(userId)).thenReturn(Optional.of(user));

            com.stripe.model.PaymentMethod mockPm = mock(com.stripe.model.PaymentMethod.class);
            when(mockPm.detach()).thenReturn(mockPm);

            try (MockedStatic<com.stripe.model.PaymentMethod> pm = mockStatic(com.stripe.model.PaymentMethod.class)) {
                pm.when(() -> com.stripe.model.PaymentMethod.retrieve("pm_abc")).thenReturn(mockPm);

                service.detachCommissionMethod(userId);

                assertThat(user.getCommissionPaymentMethodId()).isNull();
                assertThat(user.getCommissionCardBrand()).isNull();
                assertThat(user.getCommissionCardLast4()).isNull();
                verify(userRepo).save(user);
                verify(events).publishEvent(any(CommissionMethodDetachedEvent.class));
            }
        }

        @Test
        void isIdempotentWhenNoMethodRegistered() {
            UUID userId = UUID.randomUUID();
            UserEntity user = new UserEntity();
            ReflectionTestUtils.setField(user, "id", userId);
            when(userRepo.findById(userId)).thenReturn(Optional.of(user));

            service.detachCommissionMethod(userId);
            verifyNoInteractions(events);
        }
    }

    // ===================== chargeCommission =====================

    @Nested
    class ChargeCommission {

        private UUID travelerId;
        private UserEntity traveler;
        private BidEntity bid;

        @BeforeEach
        void setup() {
            travelerId = UUID.randomUUID();
            traveler = userWithCard("visa", "4242", 12, 2028);
            ReflectionTestUtils.setField(traveler, "id", travelerId);
            bid = bidWithDeclaredValue(new BigDecimal("100"), travelerId);
            lenient().when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));
            AnnouncementEntity ann = announcementWithPrice(bid.getAnnouncementId(), new BigDecimal("20.00"));
            lenient().when(announcementRepo.findById(bid.getAnnouncementId())).thenReturn(Optional.of(ann));
        }

        @Test
        void commissionIsComputedFromWeightTimesPrice() throws StripeException {
            // 5 kg × 20 €/kg = 100 € → 12 % = 12.00 € = 1 200 centimes
            ArgumentCaptor<PaymentIntentCreateParams> captor =
                    ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_amount_test");
            mockPi.setStatus("succeeded");
            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(captor.capture(), any(RequestOptions.class)))
                        .thenReturn(mockPi);
                service.chargeCommission(bid, travelerId);
                assertThat(captor.getValue().getAmount()).isEqualTo(1200L);
            }
        }

        @Test
        void successPathReturnsAccepted() throws StripeException {
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_test");
            mockPi.setStatus("succeeded");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockPi);

                AcceptBidResponse resp = service.chargeCommission(bid, travelerId);

                assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
                assertThat(bid.getCommissionPaymentIntentId()).isEqualTo("pi_test");
                verify(bidRepo).save(bid);
            }
        }

        @Test
        void requiresActionReturns3dsResponse() throws StripeException {
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_3ds");
            mockPi.setStatus("requires_action");
            mockPi.setClientSecret("pi_3ds_secret");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(mockPi);

                AcceptBidResponse resp = service.chargeCommission(bid, travelerId);

                assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.REQUIRES_3DS);
                assertThat(resp.clientSecret()).isEqualTo("pi_3ds_secret");
                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REQUIRES_3DS);
            }
        }

        @Test
        void cardDeclinedReturnsFailed() throws StripeException {
            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenThrow(new CardException("declined", null, null, "card_declined", null, null, null, null));

                AcceptBidResponse resp = service.chargeCommission(bid, travelerId);

                assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.FAILED);
                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
            }
        }

        @Test
        void throwsWhenNoCommissionMethod() {
            traveler.setCommissionPaymentMethodId(null);
            assertThatThrownBy(() -> service.chargeCommission(bid, travelerId))
                    .isInstanceOf(CommissionMethodMissingException.class);
        }

        @Test
        void isIdempotentWhenAlreadyCharged() {
            bid.setCommissionStatus(CommissionStatus.CHARGED);

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                AcceptBidResponse resp = service.chargeCommission(bid, travelerId);
                assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
                pi.verifyNoInteractions();
            }
        }

        @Test
        void successPathSetsCommissionChargedViaCard() throws StripeException {
            // FIX #2 : un débit carte réussi doit marquer commissionChargedVia=CARD
            // pour que le routage des remboursements (Stripe Refund) soit correct.
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_via");
            mockPi.setStatus("succeeded");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockPi);

                service.chargeCommission(bid, travelerId);

                assertThat(bid.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.CARD);
            }
        }

        @Test
        void expiredCardReturnsFailedWithRetryCountIncrement() throws StripeException {
            // Couvre la branche de message "expired_card" + l'incrément du compteur de retry.
            int before = bid.getCommissionRetryCount();
            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenThrow(new CardException("expired", null, "expired_card", null, null, null, null, null));

                AcceptBidResponse resp = service.chargeCommission(bid, travelerId);

                assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.FAILED);
                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
                assertThat(bid.getCommissionRetryCount()).isEqualTo(before + 1);
            }
        }
    }

    // ===================== confirmCommissionAcceptance =====================

    @Nested
    class ConfirmCommissionAcceptance {

        private BidEntity bid;
        private AnnouncementEntity announcement;
        private UUID announcementId;
        private UUID travelerId;

        @BeforeEach
        void setup() {
            announcementId = UUID.randomUUID();
            travelerId = UUID.randomUUID();

            bid = new BidEntity();
            ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
            bid.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
            bid.setCommissionPaymentIntentId("pi_xyz");
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(UUID.randomUUID());
            bid.setWeightKg(new java.math.BigDecimal("5"));
            when(bidRepo.findById(bid.getId())).thenReturn(Optional.of(bid));

            announcement = new AnnouncementEntity();
            ReflectionTestUtils.setField(announcement, "id", announcementId);
            announcement.setTravelerId(travelerId);
            announcement.setAvailableKg(new java.math.BigDecimal("20"));
            lenient().when(announcementRepo.findByIdForUpdate(announcementId))
                    .thenReturn(Optional.of(announcement));
        }

        @Test
        void transitionsToCHARGEDAndAcceptsWhenPISucceeded() throws StripeException {
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setStatus("succeeded");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.retrieve("pi_xyz")).thenReturn(mockPi);

                ConfirmAcceptanceResponse resp = service.confirmCommissionAcceptance(bid.getId());

                assertThat(resp.accepted()).isTrue();
                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
                assertThat(bid.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.CARD); // FIX #2
                assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
                verify(bidRepo).save(bid);
                verify(events).publishEvent(any(BidAcceptedEvent.class));
            }
        }

        @Test
        void isIdempotentWhenAlreadyChargedAndAccepted() {
            bid.setCommissionStatus(CommissionStatus.CHARGED);
            bid.setStatus(BidStatus.ACCEPTED);

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                ConfirmAcceptanceResponse resp = service.confirmCommissionAcceptance(bid.getId());
                assertThat(resp.accepted()).isTrue();
                pi.verifyNoInteractions();
                verify(announcementRepo, never()).findByIdForUpdate(any());
            }
        }

        @Test
        void finalizesWhenCommissionChargedButBidNotYetAccepted() {
            bid.setCommissionStatus(CommissionStatus.CHARGED);

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                ConfirmAcceptanceResponse resp = service.confirmCommissionAcceptance(bid.getId());
                assertThat(resp.accepted()).isTrue();
                assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
                pi.verifyNoInteractions();
            }
        }

        @Test
        void setsFailedWhenPINotSucceeded() throws StripeException {
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setStatus("requires_payment_method");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.retrieve("pi_xyz")).thenReturn(mockPi);

                ConfirmAcceptanceResponse resp = service.confirmCommissionAcceptance(bid.getId());

                assertThat(resp.accepted()).isFalse();
                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
            }
        }

        @Test
        void backfillsChargedViaCardWhenAlreadyChargedWithPaymentIntent() {
            // FIX #2 : un bid déjà CHARGED via PaymentIntent mais sans commissionChargedVia
            // (ancien flux) doit se voir backfiller CARD au moment du confirm, sinon le
            // remboursement ne saurait pas router vers Stripe. PI déjà à pi_xyz, via=null.
            bid.setCommissionStatus(CommissionStatus.CHARGED);
            assertThat(bid.getCommissionChargedVia()).isNull();

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                ConfirmAcceptanceResponse resp = service.confirmCommissionAcceptance(bid.getId());
                assertThat(resp.accepted()).isTrue();
                pi.verifyNoInteractions(); // CHARGED court-circuite l'appel Stripe
            }
            assertThat(bid.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.CARD);
            assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        }

        @Test
        void failsWhenNoPaymentIntentToConfirm() {
            // bid ni CHARGED ni porteur d'un PaymentIntent → rien à confirmer.
            bid.setCommissionStatus(CommissionStatus.PENDING);
            bid.setCommissionPaymentIntentId(null);

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                ConfirmAcceptanceResponse resp = service.confirmCommissionAcceptance(bid.getId());
                assertThat(resp.accepted()).isFalse();
                pi.verifyNoInteractions();
            }
        }

        @Test
        void failsGracefullyOnStripeException() throws StripeException {
            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.retrieve("pi_xyz")).thenThrow(mock(StripeException.class));

                ConfirmAcceptanceResponse resp = service.confirmCommissionAcceptance(bid.getId());

                assertThat(resp.accepted()).isFalse();
            }
        }
    }

    // ===================== acceptCashBid =====================

    @Nested
    class AcceptCashBid {

        private UUID travelerId;
        private UserEntity traveler;
        private BidEntity bid;
        private AnnouncementEntity announcement;
        private UUID announcementId;

        @BeforeEach
        void setup() {
            travelerId = UUID.randomUUID();
            announcementId = UUID.randomUUID();

            traveler = userWithCard("visa", "4242", 12, 2028);
            ReflectionTestUtils.setField(traveler, "id", travelerId);
            lenient().when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));
            // Solde wallet = 0 → fallback carte (préserve le comportement carte des tests existants)
            lenient().when(walletService.getBalance(travelerId)).thenReturn(java.math.BigDecimal.ZERO);

            bid = new BidEntity();
            ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
            bid.setPaymentMethod(com.dony.api.payments.cash.PaymentMethod.CASH);
            bid.setDeclaredValueEur(new java.math.BigDecimal("100"));
            bid.setWeightKg(new java.math.BigDecimal("5"));
            bid.setSenderId(UUID.randomUUID());
            bid.setAnnouncementId(announcementId);
            bid.setStatus(BidStatus.PENDING);
            when(bidRepo.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

            java.util.Set<com.dony.api.payments.cash.PaymentMethod> methods =
                    java.util.EnumSet.of(com.dony.api.payments.cash.PaymentMethod.CASH);
            announcement = new AnnouncementEntity();
            ReflectionTestUtils.setField(announcement, "id", announcementId);
            announcement.setTravelerId(travelerId);
            announcement.setAvailableKg(new java.math.BigDecimal("20"));
            announcement.setPricePerKg(new java.math.BigDecimal("20.00"));
            announcement.setAcceptedPaymentMethods(methods);
            lenient().when(announcementRepo.findByIdForUpdate(announcementId)).thenReturn(Optional.of(announcement));
            lenient().when(announcementRepo.findById(announcementId)).thenReturn(Optional.of(announcement));
        }

        @Test
        void walletFirstPath_sufficientBalance_debitsWalletAndFinalizesBid() {
            // Solde suffisant → débit wallet, pas de PaymentIntent Stripe
            java.math.BigDecimal commission = new java.math.BigDecimal("12.00"); // 5kg × 20€ × 12%
            when(walletService.getBalance(travelerId)).thenReturn(commission.add(java.math.BigDecimal.ONE));
            when(walletTransactionRepository.existsByUserIdAndBidIdAndType(eq(travelerId), any(), any()))
                    .thenReturn(false);

            AcceptBidResponse resp = service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.WALLET_FIRST);

            assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
            assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
            assertThat(bid.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.WALLET);
            verify(walletService).debit(eq(travelerId), any(), eq(com.dony.api.payments.wallet.WalletTransactionType.COMMISSION_DEDUCTED), any());
            verify(events).publishEvent(any(BidAcceptedEvent.class));
        }

        @Test
        void walletFirstPath_insufficientBalance_returnsInsufficientWallet() {
            // Solde 0 → pas de carte non plus
            traveler.setCommissionPaymentMethodId(null);
            when(walletService.getBalance(travelerId)).thenReturn(java.math.BigDecimal.ZERO);

            AcceptBidResponse resp = service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.WALLET_FIRST);

            assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.INSUFFICIENT_WALLET);
            assertThat(resp.hasCard()).isFalse();
            assertThat(bid.getStatus()).isNotEqualTo(BidStatus.ACCEPTED);
            verify(events, never()).publishEvent(any());
        }

        @Test
        void walletFirstPath_insufficientBalance_hasCard_returnsInsufficientWalletWithHasCardTrue() {
            when(walletService.getBalance(travelerId)).thenReturn(java.math.BigDecimal.ZERO);

            AcceptBidResponse resp = service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.WALLET_FIRST);

            assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.INSUFFICIENT_WALLET);
            assertThat(resp.hasCard()).isTrue();
        }

        @Test
        void walletFirstPath_toctouRace_returnsInsufficientWalletWithPostRaceBalance() {
            // FIX #3 : le solde est suffisant à la lecture (getBalance) mais chute entre-temps ;
            // debit() lève InsufficientWalletBalanceException. Le service doit la rattraper et
            // répondre INSUFFICIENT_WALLET (409) avec le solde réel de l'exception, PAS laisser
            // l'exception rollback-only remonter en 500.
            java.math.BigDecimal commission = new java.math.BigDecimal("12.00"); // 5kg × 20€ × 12%
            when(walletService.getBalance(travelerId)).thenReturn(commission.add(java.math.BigDecimal.TEN)); // suffisant à la lecture
            when(walletTransactionRepository.existsByUserIdAndBidIdAndType(eq(travelerId), any(), any()))
                    .thenReturn(false);
            doThrow(new com.dony.api.payments.wallet.InsufficientWalletBalanceException(
                    new java.math.BigDecimal("3.00"), commission))
                    .when(walletService).debit(eq(travelerId), any(),
                            eq(com.dony.api.payments.wallet.WalletTransactionType.COMMISSION_DEDUCTED), any());

            AcceptBidResponse resp = service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.WALLET_FIRST);

            assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.INSUFFICIENT_WALLET);
            assertThat(resp.availableBalance()).isEqualByComparingTo("3.00"); // solde post-race de l'exception
            assertThat(resp.hasCard()).isTrue();
            assertThat(bid.getStatus()).isNotEqualTo(BidStatus.ACCEPTED);
            verify(events, never()).publishEvent(any());
        }

        @Test
        void successPathFinalizesBidAsAccepted() throws StripeException {
            // Forcer le chemin CARD pour tester la logique Stripe
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_test");
            mockPi.setStatus("succeeded");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockPi);

                AcceptBidResponse resp = service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.CARD);

                assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
                assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
                assertThat(announcement.getAvailableKg()).isEqualByComparingTo("15");
                verify(events).publishEvent(any(BidAcceptedEvent.class));
            }
        }

        @Test
        void requires3dsReturnClientSecretWithoutFinalizingBid() throws StripeException {
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_3ds");
            mockPi.setStatus("requires_action");
            mockPi.setClientSecret("pi_3ds_secret");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockPi);

                AcceptBidResponse resp = service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.CARD);

                assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.REQUIRES_3DS);
                assertThat(resp.clientSecret()).isEqualTo("pi_3ds_secret");
                assertThat(bid.getStatus()).isNotEqualTo(BidStatus.ACCEPTED);
                verify(events, never()).publishEvent(any());
            }
        }

        @Test
        void throwsWhenTravelerDoesNotOwnAnnouncement() {
            announcement.setTravelerId(UUID.randomUUID());

            assertThatThrownBy(() -> service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.WALLET_FIRST))
                    .isInstanceOf(com.dony.api.common.DonyBusinessException.class);
        }

        @Test
        void throwsWhenAnnouncementDoesNotAcceptCash() {
            announcement.setAcceptedPaymentMethods(
                    java.util.EnumSet.of(com.dony.api.payments.cash.PaymentMethod.STRIPE));

            assertThatThrownBy(() -> service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.WALLET_FIRST))
                    .isInstanceOf(com.dony.api.payments.cash.exception.InvalidPaymentMethodForAnnouncementException.class);
        }

        @Test
        void isIdempotentWhenAlreadyChargedAndAccepted() {
            bid.setCommissionStatus(CommissionStatus.CHARGED);
            bid.setStatus(BidStatus.ACCEPTED);

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                AcceptBidResponse resp = service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.WALLET_FIRST);
                assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
                pi.verifyNoInteractions();
            }
        }

        @Test
        void unexpectedPiStatusReturnsFailed() throws StripeException {
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_proc");
            mockPi.setStatus("processing");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockPi);

                AcceptBidResponse resp = service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.CARD);

                assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.FAILED);
                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
                verify(bidRepo).save(bid);
            }
        }

        @Test
        void bidTakesRemainingKg_setsAnnouncementToFull() throws StripeException {
            announcement.setAvailableKg(bid.getWeightKg()); // exactly full after this bid
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_full");
            mockPi.setStatus("succeeded");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockPi);

                service.acceptCashBid(bid.getId(), travelerId, com.dony.api.payments.cash.CommissionSource.CARD);

                assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.FULL);
            }
        }
    }

    // ===================== refundCommission =====================

    @Nested
    class RefundCommission {

        private BidEntity bid;

        @BeforeEach
        void setup() {
            bid = new BidEntity();
            ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
            bid.setCommissionStatus(CommissionStatus.CHARGED);
            bid.setCommissionPaymentIntentId("pi_xyz");
        }

        @Test
        void createsRefundWithIdempotencyKey() throws StripeException {
            Refund mockRefund = new Refund();
            mockRefund.setId("re_test");

            try (MockedStatic<Refund> refund = mockStatic(Refund.class)) {
                refund.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockRefund);

                service.refundCommission(bid);

                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);
                verify(bidRepo).save(bid);

                ArgumentCaptor<RequestOptions> optCaptor = ArgumentCaptor.forClass(RequestOptions.class);
                refund.verify(() -> Refund.create(any(RefundCreateParams.class), optCaptor.capture()));
                assertThat(optCaptor.getValue().getIdempotencyKey())
                        .isEqualTo("bid_refund_" + bid.getId());
            }
        }

        @Test
        void isIdempotentWhenAlreadyRefunded() {
            bid.setCommissionStatus(CommissionStatus.REFUNDED);

            try (MockedStatic<Refund> refund = mockStatic(Refund.class)) {
                service.refundCommission(bid);
                refund.verifyNoInteractions();
            }
        }

        @Test
        void isNoOpWhenStatusNotCharged() {
            bid.setCommissionStatus(CommissionStatus.FAILED);

            try (MockedStatic<Refund> refund = mockStatic(Refund.class)) {
                service.refundCommission(bid);
                refund.verifyNoInteractions();
            }
            verify(bidRepo, never()).save(any());
        }

        @Test
        void setsRefundFailedOnStripeException() throws StripeException {
            try (MockedStatic<Refund> refund = mockStatic(Refund.class)) {
                refund.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                        .thenThrow(mock(StripeException.class));

                service.refundCommission(bid);

                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REFUND_FAILED);
                verify(bidRepo).save(bid);
            }
        }
    }

    // ===================== chargeCommissionFromWallet =====================

    @Nested
    class ChargeCommissionFromWallet {

        private UUID travelerId;
        private BidEntity bid;

        @BeforeEach
        void setup() {
            travelerId = UUID.randomUUID();
            bid = bidWithDeclaredValue(new BigDecimal("100"), travelerId);
        }

        @Test
        void debitsWalletSetsChargedViaWalletAndAudits() {
            when(walletTransactionRepository.existsByUserIdAndBidIdAndType(
                    travelerId, bid.getId(), com.dony.api.payments.wallet.WalletTransactionType.COMMISSION_DEDUCTED))
                    .thenReturn(false);

            service.chargeCommissionFromWallet(bid, travelerId, new BigDecimal("12.00"));

            verify(walletService).debit(eq(travelerId), eq(new BigDecimal("12.00")),
                    eq(com.dony.api.payments.wallet.WalletTransactionType.COMMISSION_DEDUCTED), eq(bid.getId()));
            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
            assertThat(bid.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.WALLET);
            verify(bidRepo).save(bid);
            verify(auditService).log(eq("payment"), eq(bid.getId()), eq("COMMISSION_CHARGED_WALLET"),
                    eq(travelerId), any());
        }

        @Test
        void idempotentSkipWhenCommissionAlreadyDeducted() {
            // Garde idempotence : WalletService.debit n'est pas idempotent en lui-même.
            when(walletTransactionRepository.existsByUserIdAndBidIdAndType(
                    travelerId, bid.getId(), com.dony.api.payments.wallet.WalletTransactionType.COMMISSION_DEDUCTED))
                    .thenReturn(true);

            service.chargeCommissionFromWallet(bid, travelerId, new BigDecimal("12.00"));

            verify(walletService, never()).debit(any(), any(), any(), any());
            verify(bidRepo, never()).save(any());
            verifyNoInteractions(auditService);
        }
    }

    // ===================== chargeCommissionAuto (mobile money) =====================

    @Nested
    class ChargeCommissionAuto {

        private UUID travelerId;
        private UserEntity traveler;
        private BidEntity bid;

        @BeforeEach
        void setup() {
            travelerId = UUID.randomUUID();
            traveler = userWithCard("visa", "4242", 12, 2028);
            ReflectionTestUtils.setField(traveler, "id", travelerId);
            lenient().when(userRepo.findById(travelerId)).thenReturn(Optional.of(traveler));

            bid = bidWithDeclaredValue(new BigDecimal("100"), travelerId);
            AnnouncementEntity announcement = announcementWithPrice(bid.getAnnouncementId(), new BigDecimal("20.00"));
            when(announcementRepo.findById(bid.getAnnouncementId())).thenReturn(Optional.of(announcement));
            // commission = 5 kg × 20 €/kg × 12 % = 12.00 €
        }

        @Test
        void walletSufficient_chargesFromWalletViaWallet() {
            when(walletService.getBalance(travelerId)).thenReturn(new BigDecimal("50.00"));
            when(walletTransactionRepository.existsByUserIdAndBidIdAndType(eq(travelerId), eq(bid.getId()), any()))
                    .thenReturn(false);

            service.chargeCommissionAuto(bid, travelerId);

            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
            assertThat(bid.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.WALLET);
            verify(walletService).debit(eq(travelerId), any(),
                    eq(com.dony.api.payments.wallet.WalletTransactionType.COMMISSION_DEDUCTED), eq(bid.getId()));
        }

        @Test
        void walletInsufficient_cardSucceeds_viaCard() throws StripeException {
            when(walletService.getBalance(travelerId)).thenReturn(java.math.BigDecimal.ZERO);
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_auto");
            mockPi.setStatus("succeeded");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockPi);

                service.chargeCommissionAuto(bid, travelerId);
            }

            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
            assertThat(bid.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.CARD);
        }

        @Test
        void walletInsufficient_noCard_setsFailedAndAudits() {
            when(walletService.getBalance(travelerId)).thenReturn(java.math.BigDecimal.ZERO);
            traveler.setCommissionPaymentMethodId(null); // ni wallet ni carte

            service.chargeCommissionAuto(bid, travelerId);

            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
            verify(auditService).log(eq("payment"), eq(bid.getId()), eq("COMMISSION_AUTO_FAILED"),
                    eq(travelerId), any());
        }

        @Test
        void walletInsufficient_card3ds_setsFailedNoException() throws StripeException {
            // 3DS impossible en async (pas d'interaction utilisateur) → créance FAILED, pas d'exception.
            when(walletService.getBalance(travelerId)).thenReturn(java.math.BigDecimal.ZERO);
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_3ds");
            mockPi.setStatus("requires_action");
            mockPi.setClientSecret("sec");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockPi);

                assertThatNoException().isThrownBy(() -> service.chargeCommissionAuto(bid, travelerId));
            }

            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
        }

        @Test
        void walletInsufficient_cardTransientStripeError_setsFailedNoException() throws StripeException {
            // FIX #4 : chargeCommission relève une CommissionChargeFailedException (RuntimeException)
            // sur erreur Stripe transitoire. chargeCommissionAuto doit la rattraper (catch RuntimeException)
            // et NE PAS la laisser remonter, sinon la tx REQUIRES_NEW du listener MM rollback le paiement déjà commité.
            when(walletService.getBalance(travelerId)).thenReturn(java.math.BigDecimal.ZERO);

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenThrow(mock(StripeException.class));

                assertThatNoException().isThrownBy(() -> service.chargeCommissionAuto(bid, travelerId));
            }

            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
        }

        @Test
        void walletToctouRace_fallsBackToCard() throws StripeException {
            // Solde suffisant à la lecture mais debit lève → fallback carte automatique.
            when(walletService.getBalance(travelerId)).thenReturn(new BigDecimal("50.00"));
            when(walletTransactionRepository.existsByUserIdAndBidIdAndType(eq(travelerId), eq(bid.getId()), any()))
                    .thenReturn(false);
            doThrow(new com.dony.api.payments.wallet.InsufficientWalletBalanceException(
                    java.math.BigDecimal.ZERO, new BigDecimal("12.00")))
                    .when(walletService).debit(eq(travelerId), any(), any(), eq(bid.getId()));

            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setId("pi_fb");
            mockPi.setStatus("succeeded");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockPi);

                service.chargeCommissionAuto(bid, travelerId);
            }

            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
            assertThat(bid.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.CARD);
        }
    }

    // ===================== refundCommissionToWallet =====================

    @Nested
    class RefundCommissionToWallet {

        private UUID travelerId;
        private BidEntity bid;

        @BeforeEach
        void setup() {
            travelerId = UUID.randomUUID();
            bid = new BidEntity();
            ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
            bid.setCommissionStatus(CommissionStatus.CHARGED);
        }

        private com.dony.api.payments.wallet.WalletTransactionEntity commissionTx(BigDecimal amount) {
            com.dony.api.payments.wallet.WalletTransactionEntity tx =
                    new com.dony.api.payments.wallet.WalletTransactionEntity();
            tx.setAmount(amount.negate()); // débit stocké en négatif
            tx.setType(com.dony.api.payments.wallet.WalletTransactionType.COMMISSION_DEDUCTED);
            return tx;
        }

        @Test
        void creditsWalletSetsRefundedAndAuditsWithGivenKey() {
            String key = "wallet-refund-noshow-" + bid.getId();
            when(walletTransactionRepository.findByUserIdAndBidIdAndType(
                    travelerId, bid.getId(), com.dony.api.payments.wallet.WalletTransactionType.COMMISSION_DEDUCTED))
                    .thenReturn(Optional.of(commissionTx(new BigDecimal("12.00"))));

            service.refundCommissionToWallet(bid, travelerId, key);

            verify(walletService).credit(eq(travelerId), eq(new BigDecimal("12.00")),
                    eq(com.dony.api.payments.wallet.WalletTransactionType.REFUND),
                    eq("refund-" + bid.getId()), eq(key));
            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);
            verify(bidRepo).save(bid);
            verify(auditService).log(eq("payment"), eq(bid.getId()), eq("COMMISSION_REFUNDED_TO_WALLET"),
                    eq(travelerId), any());
        }

        @Test
        void noOpWhenAlreadyRefunded() {
            bid.setCommissionStatus(CommissionStatus.REFUNDED);

            service.refundCommissionToWallet(bid, travelerId, "k");

            verify(walletService, never()).credit(any(), any(), any(), any(), any());
            verify(bidRepo, never()).save(any());
        }

        @Test
        void noOpWhenStatusNotCharged() {
            bid.setCommissionStatus(CommissionStatus.FAILED);

            service.refundCommissionToWallet(bid, travelerId, "k");

            verify(walletService, never()).credit(any(), any(), any(), any(), any());
            verify(bidRepo, never()).save(any());
        }

        @Test
        void noOpWhenNoCommissionTransactionFound() {
            // Sécurité : sans tx COMMISSION_DEDUCTED pour ce couple (traveler, bid), pas de crédit.
            when(walletTransactionRepository.findByUserIdAndBidIdAndType(
                    travelerId, bid.getId(), com.dony.api.payments.wallet.WalletTransactionType.COMMISSION_DEDUCTED))
                    .thenReturn(Optional.empty());

            service.refundCommissionToWallet(bid, travelerId, "k");

            verify(walletService, never()).credit(any(), any(), any(), any(), any());
            assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED); // inchangé
        }
    }

    // ===================== saveCommissionMethod =====================

    @Nested
    class SaveCommissionMethod {

        private UUID userId;
        private UserEntity user;

        @BeforeEach
        void setup() {
            userId = UUID.randomUUID();
            user = new UserEntity();
            ReflectionTestUtils.setField(user, "id", userId);
            when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        }

        private com.stripe.model.PaymentMethod pmWithCard() {
            com.stripe.model.PaymentMethod pm = mock(com.stripe.model.PaymentMethod.class);
            com.stripe.model.PaymentMethod.Card card = mock(com.stripe.model.PaymentMethod.Card.class);
            when(pm.getCard()).thenReturn(card);
            when(card.getBrand()).thenReturn("visa");
            when(card.getLast4()).thenReturn("4242");
            when(card.getExpMonth()).thenReturn(12L);
            when(card.getExpYear()).thenReturn(2030L);
            return pm;
        }

        @Test
        void savesCardFromRawPaymentMethodId() throws StripeException {
            com.stripe.model.PaymentMethod pm = pmWithCard();
            try (MockedStatic<com.stripe.model.PaymentMethod> pmStatic =
                    mockStatic(com.stripe.model.PaymentMethod.class)) {
                pmStatic.when(() -> com.stripe.model.PaymentMethod.retrieve("pm_abc")).thenReturn(pm);

                service.saveCommissionMethod(userId, "pm_abc");
            }
            assertThat(user.getCommissionPaymentMethodId()).isEqualTo("pm_abc");
            assertThat(user.getCommissionCardBrand()).isEqualTo("visa");
            assertThat(user.getCommissionCardLast4()).isEqualTo("4242");
            assertThat(user.getCommissionCardExpMonth()).isEqualTo(12);
            assertThat(user.getCommissionCardExpYear()).isEqualTo(2030);
            verify(userRepo).save(user);
        }

        @Test
        void resolvesPaymentMethodFromSetupIntent() throws StripeException {
            com.stripe.model.PaymentMethod pm = pmWithCard();
            SetupIntent si = mock(SetupIntent.class);
            when(si.getPaymentMethod()).thenReturn("pm_from_seti");
            try (MockedStatic<SetupIntent> siStatic = mockStatic(SetupIntent.class);
                 MockedStatic<com.stripe.model.PaymentMethod> pmStatic =
                         mockStatic(com.stripe.model.PaymentMethod.class)) {
                siStatic.when(() -> SetupIntent.retrieve("seti_123")).thenReturn(si);
                pmStatic.when(() -> com.stripe.model.PaymentMethod.retrieve("pm_from_seti")).thenReturn(pm);

                service.saveCommissionMethod(userId, "seti_123");
            }
            assertThat(user.getCommissionPaymentMethodId()).isEqualTo("pm_from_seti");
        }

        @Test
        void throwsWhenSetupIntentHasNoPaymentMethod() {
            SetupIntent si = mock(SetupIntent.class);
            when(si.getPaymentMethod()).thenReturn(null);
            try (MockedStatic<SetupIntent> siStatic = mockStatic(SetupIntent.class)) {
                siStatic.when(() -> SetupIntent.retrieve("seti_unconfirmed")).thenReturn(si);

                assertThatThrownBy(() -> service.saveCommissionMethod(userId, "seti_unconfirmed"))
                        .isInstanceOf(DonyBusinessException.class);
            }
            verify(userRepo, never()).save(any());
        }

        @Test
        void throwsWhenPaymentMethodIsNotACard() throws StripeException {
            com.stripe.model.PaymentMethod pm = mock(com.stripe.model.PaymentMethod.class);
            when(pm.getCard()).thenReturn(null);
            try (MockedStatic<com.stripe.model.PaymentMethod> pmStatic =
                    mockStatic(com.stripe.model.PaymentMethod.class)) {
                pmStatic.when(() -> com.stripe.model.PaymentMethod.retrieve("pm_nocard")).thenReturn(pm);

                assertThatThrownBy(() -> service.saveCommissionMethod(userId, "pm_nocard"))
                        .isInstanceOf(DonyBusinessException.class);
            }
            verify(userRepo, never()).save(any());
        }

        @Test
        void wrapsStripeExceptionAsRuntime() throws StripeException {
            try (MockedStatic<com.stripe.model.PaymentMethod> pmStatic =
                    mockStatic(com.stripe.model.PaymentMethod.class)) {
                pmStatic.when(() -> com.stripe.model.PaymentMethod.retrieve("pm_err"))
                        .thenThrow(mock(StripeException.class));

                assertThatThrownBy(() -> service.saveCommissionMethod(userId, "pm_err"))
                        .isInstanceOf(RuntimeException.class);
            }
        }
    }

    // ===================== acceptCashBid — gardes =====================

    @Nested
    class AcceptCashBidGuards {

        private BidEntity cashBid(UUID announcementId, BigDecimal weightKg) {
            BidEntity bid = new BidEntity();
            ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
            bid.setAnnouncementId(announcementId);
            bid.setPaymentMethod(com.dony.api.payments.cash.PaymentMethod.CASH);
            bid.setWeightKg(weightKg);
            return bid;
        }

        private AnnouncementEntity cashAnnouncement(UUID id, UUID travelerId, BigDecimal availableKg) {
            AnnouncementEntity ann = new AnnouncementEntity();
            ReflectionTestUtils.setField(ann, "id", id);
            ann.setTravelerId(travelerId);
            ann.setAvailableKg(availableKg);
            ann.setPricePerKg(new BigDecimal("20.00"));
            ann.setAcceptedPaymentMethods(
                    java.util.EnumSet.of(com.dony.api.payments.cash.PaymentMethod.CASH));
            return ann;
        }

        @Test
        void throwsWhenBidNotFound() {
            UUID bidId = UUID.randomUUID();
            when(bidRepo.findByIdForUpdate(bidId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptCashBid(bidId, UUID.randomUUID(),
                    com.dony.api.payments.cash.CommissionSource.WALLET_FIRST))
                    .isInstanceOf(DonyBusinessException.class);
        }

        @Test
        void throwsWhenAnnouncementNotFound() {
            UUID announcementId = UUID.randomUUID();
            BidEntity bid = cashBid(announcementId, new BigDecimal("5"));
            when(bidRepo.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
            when(announcementRepo.findByIdForUpdate(announcementId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptCashBid(bid.getId(), UUID.randomUUID(),
                    com.dony.api.payments.cash.CommissionSource.WALLET_FIRST))
                    .isInstanceOf(DonyBusinessException.class);
        }

        @Test
        void throwsWhenBidPaymentMethodIsNotCash() {
            UUID travelerId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();
            BidEntity bid = cashBid(announcementId, new BigDecimal("5"));
            bid.setPaymentMethod(com.dony.api.payments.cash.PaymentMethod.STRIPE); // pas cash
            AnnouncementEntity ann = cashAnnouncement(announcementId, travelerId, new BigDecimal("20"));
            when(bidRepo.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
            when(announcementRepo.findByIdForUpdate(announcementId)).thenReturn(Optional.of(ann));

            assertThatThrownBy(() -> service.acceptCashBid(bid.getId(), travelerId,
                    com.dony.api.payments.cash.CommissionSource.WALLET_FIRST))
                    .isInstanceOf(
                            com.dony.api.payments.cash.exception.InvalidPaymentMethodForAnnouncementException.class);
        }

        @Test
        void throwsWhenCapacityInsufficient() {
            UUID travelerId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();
            BidEntity bid = cashBid(announcementId, new BigDecimal("30")); // > available
            AnnouncementEntity ann = cashAnnouncement(announcementId, travelerId, new BigDecimal("20"));
            when(bidRepo.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
            when(announcementRepo.findByIdForUpdate(announcementId)).thenReturn(Optional.of(ann));

            assertThatThrownBy(() -> service.acceptCashBid(bid.getId(), travelerId,
                    com.dony.api.payments.cash.CommissionSource.WALLET_FIRST))
                    .isInstanceOf(DonyBusinessException.class);
        }
    }
}
