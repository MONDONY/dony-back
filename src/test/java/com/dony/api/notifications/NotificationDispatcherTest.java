package com.dony.api.notifications;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.events.TripCancelledEvent;
import com.dony.api.disputes.events.DisputeOpenedEvent;
import com.dony.api.matching.events.BidAcceptedEvent;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.matching.events.BidRejectedEvent;
import com.dony.api.matching.events.HandoverDefinedEvent;
import com.dony.api.payments.events.PaymentReleasedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock FcmService fcmService;
    @Mock SmsService smsService;
    @Mock UserRepository userRepository;
    @Mock NotificationService notificationService;

    NotificationDispatcher dispatcher;

    private final UUID senderId   = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId      = UUID.randomUUID();
    private final UUID annId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationDispatcher(fcmService, smsService, userRepository, notificationService);
        // persist() must return an entity with a non-null ID (JPA doesn't run in unit tests)
        var stubEntity = new NotificationEntity(UUID.randomUUID(), "STUB", "stub", "stub", Map.of(), false);
        setEntityId(stubEntity, UUID.randomUUID());
        // lenient: no-op tests don't call persist(), so avoid UnnecessaryStubbing failure
        lenient().when(notificationService.persist(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(stubEntity);
        lenient().when(notificationService.persist(any(), any(), any(), any(), any())).thenReturn(stubEntity);
    }

    private void setEntityId(NotificationEntity entity, UUID id) {
        try {
            var field = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── BidCreatedEvent ───────────────────────────────────────────────────────

    @Test
    void onBidCreated_notifiesTraveler() {
        BidCreatedEvent event = new BidCreatedEvent(
                bidId, annId, travelerId, senderId, "Mariama", BigDecimal.valueOf(3.5), "Paris → Dakar");
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidCreated(event);

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(travelerId), eq("Nouvelle demande d'envoi"), contains("Mariama"), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("type", "BID_CREATED");
    }

    // ── BidAcceptedEvent ──────────────────────────────────────────────────────

    @Test
    void onBidAccepted_notifiesSenderWithTravelerName() {
        UserEntity traveler = new UserEntity();
        traveler.setFirstName("Ibrahima");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidAccepted(new BidAcceptedEvent(bidId, senderId, travelerId, annId));

        verify(fcmService).sendToUser(eq(senderId), eq("Demande acceptée !"), contains("Ibrahima"), any());
    }

    @Test
    void onBidAccepted_fallbackNameWhenUserHasNoFirstName() {
        UserEntity traveler = new UserEntity();
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidAccepted(new BidAcceptedEvent(bidId, senderId, travelerId, annId));

        verify(fcmService).sendToUser(eq(senderId), eq("Demande acceptée !"), contains("Le voyageur"), any());
    }

    // ── BidRejectedEvent ──────────────────────────────────────────────────────

    @Test
    void onBidRejected_notifiesSender() {
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidRejected(new BidRejectedEvent(bidId, senderId, "wrong content"));

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Demande refusée"), any(), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("type", "BID_REJECTED");
    }

    // ── HandoverDefinedEvent ──────────────────────────────────────────────────

    @Test
    void onHandoverDefined_notifiesSenderWithLocationAndDate() {
        LocalDateTime start = LocalDateTime.of(2026, 5, 10, 14, 30);
        HandoverDefinedEvent event = new HandoverDefinedEvent(bidId, senderId, "Gare du Nord", start, start.plusHours(2));
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onHandoverDefined(event);

        verify(fcmService).sendToUser(eq(senderId), eq("Point de remise défini"),
                contains("Gare du Nord"), any());
    }

    // ── TripCancelledEvent ────────────────────────────────────────────────────

    @Test
    void onTripCancelled_notifiesAllAffectedSenders() {
        UUID sender2 = UUID.randomUUID();
        TripCancelledEvent event = new TripCancelledEvent(
                annId, travelerId, List.of(senderId, sender2), "sick", List.of(bidId));
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onTripCancelled(event);

        verify(fcmService).sendToUser(eq(senderId), eq("Trajet annulé"), any(), any());
        verify(fcmService).sendToUser(eq(sender2),  eq("Trajet annulé"), any(), any());
    }

    @Test
    void onTripCancelled_noopWhenNoSenders() {
        TripCancelledEvent event = new TripCancelledEvent(annId, travelerId, null, "sick", List.of());

        dispatcher.onTripCancelled(event);

        verifyNoInteractions(fcmService);
    }

    // ── DeliveryConfirmedEvent ────────────────────────────────────────────────

    @Test
    void onDeliveryConfirmed_notifiesSender() {
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, senderId, travelerId));

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Livraison confirmée"), any(), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("type", "DELIVERY_CONFIRMED");
    }

    // ── PaymentReleasedEvent ──────────────────────────────────────────────────

    @Test
    void onPaymentReleased_notifiesTravelerWithAmount() {
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onPaymentReleased(new PaymentReleasedEvent(bidId, travelerId, senderId, BigDecimal.valueOf(45.00)));

        verify(fcmService).sendToUser(eq(travelerId), eq("Paiement reçu !"), contains("45,00 €"), any());
    }

    // ── DisputeOpenedEvent ────────────────────────────────────────────────────

    @Test
    void onDisputeOpened_notifiesBothParties() {
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onDisputeOpened(new DisputeOpenedEvent(bidId, senderId, travelerId));

        verify(fcmService).sendToUser(eq(senderId),   eq("Litige ouvert"), any(), any());
        verify(fcmService).sendToUser(eq(travelerId), eq("Litige ouvert"), any(), any());
    }

    // ── notifyBySms ──────────────────────────────────────────────────────────

    @Test
    void notifyBySms_delegatesToSmsService() {
        dispatcher.notifyBySms("+221701234567", "Bonjour");
        verify(smsService).send("+221701234567", "Bonjour");
    }
}
