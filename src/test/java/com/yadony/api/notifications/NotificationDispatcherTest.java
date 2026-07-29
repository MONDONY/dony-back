package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.events.BidLostRematchPreparedEvent;
import com.yadony.api.cancellation.events.DeliveryNoShowReportedEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
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
            var field = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
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

    // ── AnnouncementInProgressEvent ───────────────────────────────────────────

    /**
     * « Bon voyage ! » n'appelle aucune action et n'apprend rien au voyageur : c'est lui qui a
     * saisi la date de départ. La notification reste persistée pour la boîte de réception,
     * mais ne doit plus atteindre FCM.
     */
    @Test
    void onAnnouncementInProgress_persistsWithoutPush() {
        dispatcher.onAnnouncementInProgress(
                new com.yadony.api.matching.events.AnnouncementInProgressEvent(annId, travelerId));

        verify(notificationService).persist(eq(travelerId), eq("TRIP_IN_PROGRESS"),
                eq("Bon voyage !"), any(), any(), eq(false));
        verify(fcmService, never()).sendToUser(any(), any(), any(), any());
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

    /**
     * Wave et Orange Money : {@code MobileMoneyBidAcceptedListener} envoie « Payez votre
     * trajet », qui annonce déjà l'acceptation et porte le lien de paiement. Le push
     * générique ferait un second réveil du téléphone pour la même action, en répétant la
     * première. La trace reste persistée pour la boîte de réception.
     */
    @Test
    void onBidAccepted_mobileMoney_persistsWithoutPush() {
        UserEntity traveler = new UserEntity();
        traveler.setFirstName("Ibrahima");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        dispatcher.onBidAccepted(new BidAcceptedEvent(bidId, senderId, travelerId, annId, true));

        verify(notificationService).persist(eq(senderId), eq("BID_ACCEPTED"),
                eq("Demande acceptée !"), any(), any(), eq(false));
        verify(fcmService, never()).sendToUser(any(), any(), any(), any());
    }

    @Test
    void onBidAccepted_nonMobileMoney_keepsPush() {
        UserEntity traveler = new UserEntity();
        traveler.setFirstName("Ibrahima");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidAccepted(new BidAcceptedEvent(bidId, senderId, travelerId, annId, false));

        verify(fcmService).sendToUser(eq(senderId), eq("Demande acceptée !"), any(), any());
    }

    // ── BidRejectedEvent ──────────────────────────────────────────────────────

    @Test
    void onBidRejected_notEligible_keepsGenericNotification() {
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidRejected(new BidRejectedEvent(bidId, senderId, "wrong content"));

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Demande refusée"), any(), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("type", "BID_REJECTED");
    }

    @Test
    void onBidRejected_rematchEligible_skipsGenericNotification() {
        BidRejectedEvent event = new BidRejectedEvent(bidId, senderId, "cancelled by traveler", annId, true);

        dispatcher.onBidRejected(event);

        verifyNoInteractions(fcmService, notificationService);
    }

    // ── BidLostRematchPreparedEvent ───────────────────────────────────────────

    @Test
    void onBidLostRematchPrepared_cancelWithSuggestions_notifiesWithDeepLink() {
        UUID cancellationId = UUID.randomUUID();
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidLostRematchPrepared(
                new BidLostRematchPreparedEvent(senderId, bidId, cancellationId, 3, true));

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Transport annulé"),
                eq("Le voyageur a annulé le transport de votre colis — remboursement en cours. "
                        + "3 voyageurs alternatifs disponibles"),
                dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("type", "BID_REJECTED");
        assertThat(dataCaptor.getValue()).containsEntry("bidId", bidId.toString());
        assertThat(dataCaptor.getValue()).containsEntry("cancellationId", cancellationId.toString());
    }

    @Test
    void onBidLostRematchPrepared_singleSuggestion_usesSingularWording() {
        UUID cancellationId = UUID.randomUUID();
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidLostRematchPrepared(
                new BidLostRematchPreparedEvent(senderId, bidId, cancellationId, 1, true));

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Transport annulé"),
                eq("Le voyageur a annulé le transport de votre colis — remboursement en cours. "
                        + "1 voyageur alternatif disponible"),
                dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("cancellationId", cancellationId.toString());
    }

    @Test
    void onBidLostRematchPrepared_cancelZero_refundOnlyBody() {
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidLostRematchPrepared(
                new BidLostRematchPreparedEvent(senderId, bidId, null, 0, true));

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Transport annulé"),
                eq("Le voyageur a annulé le transport de votre colis — votre remboursement est en cours"),
                dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("type", "BID_REJECTED");
        assertThat(dataCaptor.getValue()).containsEntry("bidId", bidId.toString());
        assertThat(dataCaptor.getValue()).doesNotContainKey("cancellationId");
    }

    @Test
    void onBidLostRematchPrepared_rejectWithSuggestions_usesRejectWording() {
        UUID cancellationId = UUID.randomUUID();
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidLostRematchPrepared(
                new BidLostRematchPreparedEvent(senderId, bidId, cancellationId, 2, false));

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Demande refusée"),
                eq("Le voyageur a refusé votre demande — remboursement en cours. "
                        + "2 voyageurs alternatifs disponibles"),
                dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("cancellationId", cancellationId.toString());
    }

    @Test
    void onBidLostRematchPrepared_rejectZero_refundOnlyBody() {
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidLostRematchPrepared(
                new BidLostRematchPreparedEvent(senderId, bidId, null, 0, false));

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Demande refusée"),
                eq("Le voyageur a refusé votre demande — votre remboursement est en cours"),
                dataCaptor.capture());
        assertThat(dataCaptor.getValue()).doesNotContainKey("cancellationId");
    }

    @Test
    void onBidLostRematchPrepared_countPositiveButNullCancellationId_treatedAsZero() {
        // Défense : ne devrait pas arriver côté X2, mais si ça arrive on ne doit ni NPE
        // ni envoyer un deep link cassé — on retombe sur le corps "refund only" sans cancellationId.
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onBidLostRematchPrepared(
                new BidLostRematchPreparedEvent(senderId, bidId, null, 2, true));

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Transport annulé"),
                eq("Le voyageur a annulé le transport de votre colis — votre remboursement est en cours"),
                dataCaptor.capture());
        assertThat(dataCaptor.getValue()).doesNotContainKey("cancellationId");
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

    @Test
    void onTripCancelled_withSuggestions_notifiesWithDeepLink() {
        UUID cancellationId = UUID.randomUUID();
        Map<UUID, TripCancelledEvent.RematchBySenderInfo> rematchBySender = Map.of(
                senderId, new TripCancelledEvent.RematchBySenderInfo(cancellationId, 3));
        TripCancelledEvent event = new TripCancelledEvent(
                annId, travelerId, List.of(senderId), "sick", List.of(bidId),
                Map.of(), Map.of(), rematchBySender);
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onTripCancelled(event);

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Trajet annulé"),
                eq("Trajet annulé — remboursement en cours. 3 voyageurs alternatifs disponibles"),
                dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("type", "TRIP_CANCELLED");
        assertThat(dataCaptor.getValue()).containsEntry("cancellationId", cancellationId.toString());
    }

    @Test
    void onTripCancelled_withSingleSuggestion_usesSingularWording() {
        UUID cancellationId = UUID.randomUUID();
        Map<UUID, TripCancelledEvent.RematchBySenderInfo> rematchBySender = Map.of(
                senderId, new TripCancelledEvent.RematchBySenderInfo(cancellationId, 1));
        TripCancelledEvent event = new TripCancelledEvent(
                annId, travelerId, List.of(senderId), "sick", List.of(bidId),
                Map.of(), Map.of(), rematchBySender);
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onTripCancelled(event);

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Trajet annulé"),
                eq("Trajet annulé — remboursement en cours. 1 voyageur alternatif disponible"),
                dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("type", "TRIP_CANCELLED");
        assertThat(dataCaptor.getValue()).containsEntry("cancellationId", cancellationId.toString());
    }

    @Test
    void onTripCancelled_withoutSuggestions_notifiesRefundOnly() {
        UUID cancellationId = UUID.randomUUID();
        Map<UUID, TripCancelledEvent.RematchBySenderInfo> rematchBySender = Map.of(
                senderId, new TripCancelledEvent.RematchBySenderInfo(cancellationId, 0));
        TripCancelledEvent event = new TripCancelledEvent(
                annId, travelerId, List.of(senderId), "sick", List.of(bidId),
                Map.of(), Map.of(), rematchBySender);
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onTripCancelled(event);

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Trajet annulé"),
                eq("Trajet annulé — Aucun voyageur disponible dans les 72h, votre remboursement est traité"),
                dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("type", "TRIP_CANCELLED");
        assertThat(dataCaptor.getValue()).doesNotContainKey("cancellationId");
    }

    @Test
    void onTripCancelled_missingRematchInfo_keepsLegacyBody() {
        // Constructeur legacy (5 args) → rematchBySender vide, chemin cancelAfterHandover inchangé
        TripCancelledEvent event = new TripCancelledEvent(
                annId, travelerId, List.of(senderId), "sick", List.of(bidId));
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onTripCancelled(event);

        var dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), eq("Trajet annulé"),
                eq("Le voyageur a annulé son trajet. Remboursement en cours."),
                dataCaptor.capture());
        assertThat(dataCaptor.getValue()).doesNotContainKey("cancellationId");
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

    // ── DeliveryNoShowReportedEvent ───────────────────────────────────────────

    @Test
    void onDeliveryNoShowReportedByTraveler_notifiesSenderOnly() {
        UUID newBidId = UUID.randomUUID(), newSenderId = UUID.randomUUID(), newTravelerId = UUID.randomUUID();
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onDeliveryNoShowReported(
                new DeliveryNoShowReportedEvent(newBidId, newSenderId, newTravelerId, true));

        verify(notificationService).persist(eq(newSenderId), eq("DELIVERY_NOSHOW_REPORTED"), any(), any(), any(), eq(false));
        verify(fcmService).sendToUser(eq(newSenderId), any(), any(), any());
        verifyNoInteractions(smsService);
    }

    @Test
    void onDeliveryNoShowReportedBySender_notifiesTravelerOnly() {
        UUID newBidId = UUID.randomUUID(), newSenderId = UUID.randomUUID(), newTravelerId = UUID.randomUUID();
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.onDeliveryNoShowReported(
                new DeliveryNoShowReportedEvent(newBidId, newSenderId, newTravelerId, false));

        verify(notificationService).persist(eq(newTravelerId), eq("DELIVERY_NOSHOW_REPORTED"), any(), any(), any(), eq(false));
        verify(fcmService).sendToUser(eq(newTravelerId), any(), any(), any());
    }

    // ── notifyBySms ──────────────────────────────────────────────────────────

    @Test
    void notifyBySms_delegatesToSmsService() {
        dispatcher.notifyBySms("+221701234567", "Bonjour");
        verify(smsService).send("+221701234567", "Bonjour");
    }

    // ── notifyUser(push on/off) ───────────────────────────────────────────────

    @Test
    void notifyUser_withPushFalse_persistsInAppButNoFcm() {
        UUID userId = UUID.randomUUID();

        dispatcher.notifyUser(userId, "T", "B", Map.of("type", "X"), false);

        verify(notificationService).persist(eq(userId), eq("X"), eq("T"), eq("B"), anyMap(), eq(false));
        verifyNoInteractions(fcmService);
    }

    @Test
    void notifyUser_withPushTrue_persistsAndSendsFcm() {
        UUID userId = UUID.randomUUID();
        when(fcmService.sendToUser(any(), any(), any(), any())).thenReturn(true);

        dispatcher.notifyUser(userId, "T", "B", Map.of("type", "X"), true);

        verify(notificationService).persist(eq(userId), eq("X"), eq("T"), eq("B"), anyMap(), eq(false));
        verify(fcmService).sendToUser(eq(userId), eq("T"), eq("B"), anyMap());
    }
}
