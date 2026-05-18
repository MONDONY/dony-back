package com.dony.api.common.metrics;

import com.dony.api.auth.events.UserRegisteredEvent;
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
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Instrumentation métier centralisée. Écoute les événements de domaine déjà
 * publiés par l'application et incrémente des compteurs Micrometer. Aucun
 * service métier n'est modifié — la communication reste event-driven,
 * conformément à la règle d'architecture « pas d'injection cross-package ».
 *
 * Les compteurs sont enregistrés paresseusement par Micrometer : une série
 * n'apparaît dans /actuator/prometheus qu'après sa première occurrence.
 */
@Component
public class BusinessMetricsListener {

    private final MeterRegistry registry;

    public BusinessMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onUserRegistered(UserRegisteredEvent event) {
        registry.counter("dony.users.registered").increment();
    }

    @EventListener
    public void onAnnouncementCreated(AnnouncementCreatedEvent event) {
        registry.counter("dony.announcements.created").increment();
    }

    @EventListener
    public void onBidCreated(BidCreatedEvent event) {
        registry.counter("dony.bids.created", "corridor", safe(event.getCorridor()))
                .increment();
    }

    @EventListener
    public void onBidAccepted(BidAcceptedEvent event) {
        registry.counter("dony.bids.accepted").increment();
    }

    @EventListener
    public void onBidRejected(BidRejectedEvent event) {
        registry.counter("dony.bids.rejected").increment();
    }

    @EventListener
    public void onPaymentEscrowReady(PaymentEscrowReadyEvent event) {
        registry.counter("dony.payments.escrow_ready").increment();
    }

    @EventListener
    public void onPaymentReleased(PaymentReleasedEvent event) {
        registry.counter("dony.payments.released").increment();
    }

    @EventListener
    public void onKycVerified(UserKycVerifiedEvent event) {
        registry.counter("dony.kyc.verified").increment();
    }

    @EventListener
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        registry.counter("dony.deliveries.confirmed").increment();
    }

    @EventListener
    public void onDisputeOpened(DisputeOpenedEvent event) {
        registry.counter("dony.disputes.opened").increment();
    }

    @EventListener
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        String reason = event.reason() == null ? "unknown" : event.reason().name();
        registry.counter("dony.cancellations.confirmed", "reason", reason).increment();
    }

    @EventListener
    public void onTravelerNoShow(VoyageurNoShowEvent event) {
        registry.counter("dony.travelers.no_show").increment();
    }

    private static String safe(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }
}
