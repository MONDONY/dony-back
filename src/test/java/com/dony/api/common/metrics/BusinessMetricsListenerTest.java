package com.dony.api.common.metrics;

import com.dony.api.auth.events.UserRegisteredEvent;
import com.dony.api.cancellation.CancellationReason;
import com.dony.api.cancellation.events.CancellationConfirmedEvent;
import com.dony.api.disputes.events.DisputeOpenedEvent;
import com.dony.api.kyc.events.UserKycVerifiedEvent;
import com.dony.api.matching.events.AnnouncementCreatedEvent;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.matching.events.VoyageurNoShowEvent;
import com.dony.api.payments.events.PaymentEscrowReadyEvent;
import com.dony.api.payments.events.PaymentReleasedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMetricsListenerTest {

    private SimpleMeterRegistry registry;
    private BusinessMetricsListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new BusinessMetricsListener(registry);
    }

    @Test
    void onUserRegistered_incrementsCounter() {
        listener.onUserRegistered(new UserRegisteredEvent(UUID.randomUUID(), "fb-uid"));
        assertThat(registry.counter("dony.users.registered").count()).isEqualTo(1.0);
    }

    @Test
    void onBidCreated_incrementsCounterWithCorridorTag() {
        listener.onBidCreated(new BidCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Awa", new BigDecimal("5.0"), "PAR-DKR"));
        assertThat(registry.counter("dony.bids.created", "corridor", "PAR-DKR").count())
                .isEqualTo(1.0);
    }

    @Test
    void onBidCreated_nullCorridor_usesUnknownTag() {
        listener.onBidCreated(new BidCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Awa", new BigDecimal("5.0"), null));
        assertThat(registry.counter("dony.bids.created", "corridor", "unknown").count())
                .isEqualTo(1.0);
    }

    @Test
    void onDeliveryConfirmed_incrementsCounter() {
        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertThat(registry.counter("dony.deliveries.confirmed").count()).isEqualTo(1.0);
    }

    @Test
    void onCancellationConfirmed_usesReasonTag() {
        listener.onCancellationConfirmed(new CancellationConfirmedEvent(
                UUID.randomUUID(), UUID.randomUUID(), CancellationReason.TRIP_CANCELLED));
        assertThat(registry.counter("dony.cancellations.confirmed",
                "reason", "TRIP_CANCELLED").count()).isEqualTo(1.0);
    }

    @Test
    void onBidAccepted_multipleEvents_accumulate() {
        listener.onBidAccepted(new BidAcceptedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        listener.onBidAccepted(new BidAcceptedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertThat(registry.counter("dony.bids.accepted").count()).isEqualTo(2.0);
    }

    @Test
    void onAnnouncementCreated_incrementsCounter() {
        listener.onAnnouncementCreated(new AnnouncementCreatedEvent(
                UUID.randomUUID(), "Paris", "FR", "Dakar", "SN"));
        assertThat(registry.counter("dony.announcements.created").count()).isEqualTo(1.0);
    }

    @Test
    void onBidRejected_incrementsCounter() {
        listener.onBidRejected(new BidRejectedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "trop cher"));
        assertThat(registry.counter("dony.bids.rejected").count()).isEqualTo(1.0);
    }

    @Test
    void onPaymentEscrowReady_incrementsCounter() {
        listener.onPaymentEscrowReady(new PaymentEscrowReadyEvent(
                UUID.randomUUID(), UUID.randomUUID()));
        assertThat(registry.counter("dony.payments.escrow_ready").count()).isEqualTo(1.0);
    }

    @Test
    void onPaymentReleased_incrementsCounter() {
        listener.onPaymentReleased(new PaymentReleasedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("42.00")));
        assertThat(registry.counter("dony.payments.released").count()).isEqualTo(1.0);
    }

    @Test
    void onKycVerified_incrementsCounter() {
        listener.onKycVerified(new UserKycVerifiedEvent(
                UUID.randomUUID(), "+221770000000"));
        assertThat(registry.counter("dony.kyc.verified").count()).isEqualTo(1.0);
    }

    @Test
    void onDisputeOpened_incrementsCounter() {
        listener.onDisputeOpened(new DisputeOpenedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertThat(registry.counter("dony.disputes.opened").count()).isEqualTo(1.0);
    }

    @Test
    void onTravelerNoShow_incrementsCounter() {
        listener.onTravelerNoShow(new VoyageurNoShowEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1));
        assertThat(registry.counter("dony.travelers.no_show").count()).isEqualTo(1.0);
    }

    @Test
    void onCancellationConfirmed_nullReason_usesUnknownTag() {
        listener.onCancellationConfirmed(new CancellationConfirmedEvent(
                UUID.randomUUID(), UUID.randomUUID(), null));
        assertThat(registry.counter("dony.cancellations.confirmed",
                "reason", "unknown").count()).isEqualTo(1.0);
    }
}
