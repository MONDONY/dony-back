package com.dony.api.payments;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.stripe.AdminAlertService;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.payments.events.PaymentReleasedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.param.TransferCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BidRepository bidRepository;
    @Mock private AdminAlertService adminAlert;

    private DeliveryEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeliveryEventListener(paymentRepository, userRepository,
                auditService, eventPublisher, bidRepository, adminAlert);
    }

    private PaymentEntity payment(boolean legacy, PaymentStatus status, String chargeId) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setStripePaymentIntentId("pi_xxx");
        p.setStripeChargeId(chargeId);
        p.setStatus(status);
        p.setLegacyDestinationCharge(legacy);
        p.setAmount(new BigDecimal("30.00"));
        p.setCommissionAmount(new BigDecimal("3.60"));
        return p;
    }

    private DeliveryConfirmedEvent event(UUID bidId, UUID travelerId) {
        return new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId);
    }

    private UserEntity traveler() {
        UserEntity u = new UserEntity();
        u.setStripeAccountId("acct_xyz");
        return u;
    }

    @Test
    void legacy_path_calls_capture() throws StripeException {
        PaymentEntity p = payment(true, PaymentStatus.ESCROW, "ch_legacy");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.capture()).thenReturn(pi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));

            verify(pi).capture();
            transferStatic.verifyNoInteractions();
        }

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(p.getEscrowReleasedAt()).isNotNull();
        verify(auditService).log(eq("PAYMENT"), any(), eq("ESCROW_RELEASED_LEGACY"), any(), any());
        verify(eventPublisher).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void v2_path_calls_transfer_with_net_amount_in_cents() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_new");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            Transfer transfer = mock(Transfer.class);
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            transferStatic.when(() -> Transfer.create(captor.capture())).thenReturn(transfer);

            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));

            piStatic.verifyNoInteractions();
            TransferCreateParams params = captor.getValue();
            assertThat(params.getAmount()).isEqualTo(2640L); // (30 - 3.60) * 100
            assertThat(params.getCurrency()).isEqualTo("eur");
            assertThat(params.getDestination()).isEqualTo("acct_xyz");
            assertThat(params.getSourceTransaction()).isEqualTo("ch_new");
        }

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        verify(auditService).log(eq("PAYMENT"), any(), eq("ESCROW_RELEASED_TRANSFER"), any(), any());
        verify(eventPublisher).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void idempotent_when_already_released() {
        PaymentEntity p = payment(false, PaymentStatus.RELEASED, "ch_x");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));
            piStatic.verifyNoInteractions();
            transferStatic.verifyNoInteractions();
        }
        verifyNoInteractions(auditService);
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void no_op_when_payment_not_found() {
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        assertThatNoException().isThrownBy(() ->
                listener.handleDeliveryConfirmed(event(bidId, UUID.randomUUID())));
        verifyNoInteractions(auditService);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void v2_path_without_charge_id_omits_source_transaction() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, null);
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            transferStatic.when(() -> Transfer.create(captor.capture())).thenReturn(mock(Transfer.class));

            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));

            assertThat(captor.getValue().getSourceTransaction()).isNull();
        }
    }

    @Test
    void cash_bid_skips_stripe_operations() {
        UUID bidId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.CASH);
        when(bidRepository.findById(bidId)).thenReturn(java.util.Optional.of(bid));

        try (MockedStatic<com.stripe.model.PaymentIntent> piStatic =
                     mockStatic(com.stripe.model.PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(event(bidId, UUID.randomUUID()));
            piStatic.verifyNoInteractions();
            transferStatic.verifyNoInteractions();
        }
        verifyNoInteractions(paymentRepository, auditService);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void transfer_failure_is_logged_not_thrown() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_fail");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            transferStatic.when(() -> Transfer.create(any(TransferCreateParams.class)))
                    .thenThrow(mock(com.stripe.exception.InvalidRequestException.class));

            assertThatNoException().isThrownBy(() ->
                    listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId)));
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.ESCROW); // unchanged
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }
}
