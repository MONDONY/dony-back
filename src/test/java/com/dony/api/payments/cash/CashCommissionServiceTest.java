package com.dony.api.payments.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
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
    @Mock private ApplicationEventPublisher events;

    private final CommissionProperties props =
            new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24);

    private CashCommissionService service;

    @BeforeEach
    void setUp() {
        service = new CashCommissionService(props, userRepo, bidRepo, events);
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
        b.setPaymentMethod(com.dony.api.payments.cash.PaymentMethod.CASH);
        return b;
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
    }

    // ===================== confirmCommissionAcceptance =====================

    @Nested
    class ConfirmCommissionAcceptance {

        private BidEntity bid;

        @BeforeEach
        void setup() {
            bid = new BidEntity();
            ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
            bid.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
            bid.setCommissionPaymentIntentId("pi_xyz");
            when(bidRepo.findById(bid.getId())).thenReturn(Optional.of(bid));
        }

        @Test
        void transitionsToCHARGEDWhenPISucceeded() throws StripeException {
            PaymentIntent mockPi = new PaymentIntent();
            mockPi.setStatus("succeeded");

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                pi.when(() -> PaymentIntent.retrieve("pi_xyz")).thenReturn(mockPi);

                ConfirmAcceptanceResponse resp = service.confirmCommissionAcceptance(bid.getId());

                assertThat(resp.accepted()).isTrue();
                assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
                verify(bidRepo).save(bid);
            }
        }

        @Test
        void isIdempotentWhenAlreadyCharged() {
            bid.setCommissionStatus(CommissionStatus.CHARGED);

            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
                ConfirmAcceptanceResponse resp = service.confirmCommissionAcceptance(bid.getId());
                assertThat(resp.accepted()).isTrue();
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
}
