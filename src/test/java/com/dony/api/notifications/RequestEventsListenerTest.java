package com.dony.api.notifications;

import com.dony.api.requests.event.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestEventsListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private RequestEventsListener listener;

    @Test
    void onNegotiationStarted_notifiesSender() {
        UUID senderId = UUID.randomUUID();
        var event = new NegotiationStartedEvent(
            UUID.randomUUID(), UUID.randomUUID(), senderId, UUID.randomUUID(),
            new BigDecimal("30")
        );

        listener.onNegotiationStarted(event);

        verify(dispatcher).notifyUser(eq(senderId), contains("proposition"), anyString(), anyMap());
    }

    @Test
    void onNegotiationCounterPosted_notifiesToUser() {
        UUID toUserId = UUID.randomUUID();
        var event = new NegotiationCounterPostedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), toUserId,
            new BigDecimal("25"), 2
        );

        listener.onNegotiationCounterPosted(event);

        verify(dispatcher).notifyUser(eq(toUserId), contains("contre-proposition"), anyString(), anyMap());
    }

    @Test
    void onPackageRequestAccepted_notifiesBothParties() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        var event = new PackageRequestAcceptedEvent(
            UUID.randomUUID(), UUID.randomUUID(),
            senderId, travelerId, new BigDecimal("30"), null,
            new BigDecimal("5"), "test colis", "vetements", "pi_test_123"
        );

        listener.onPackageRequestAccepted(event);

        verify(dispatcher).notifyUser(eq(travelerId), contains("Paiement reçu"), anyString(), anyMap());
        verify(dispatcher).notifyUser(eq(senderId), contains("finalisée"), anyString(), anyMap());
    }

    @Test
    void onNegotiationAwaitingTrip_notifiesTraveler() {
        UUID travelerId = UUID.randomUUID();
        var event = new com.dony.api.requests.event.NegotiationAwaitingTripEvent(
            UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), travelerId, new BigDecimal("30")
        );

        listener.onNegotiationAwaitingTrip(event);

        verify(dispatcher).notifyUser(eq(travelerId), contains("acceptée"), anyString(), anyMap());
    }

    @Test
    void onNegotiationAwaitingPayment_notifiesSender() {
        UUID senderId = UUID.randomUUID();
        var event = new com.dony.api.requests.event.NegotiationAwaitingPaymentEvent(
            UUID.randomUUID(), UUID.randomUUID(),
            senderId, UUID.randomUUID(), new BigDecimal("30"), UUID.randomUUID()
        );

        listener.onNegotiationAwaitingPayment(event);

        verify(dispatcher).notifyUser(eq(senderId), contains("paiement"), anyString(), anyMap());
    }

    @Test
    void onPackageRequestExpired_notifiesSender() {
        UUID senderId = UUID.randomUUID();
        var event = new PackageRequestExpiredEvent(UUID.randomUUID(), senderId);

        listener.onPackageRequestExpired(event);

        verify(dispatcher).notifyUser(eq(senderId), contains("expiré"), anyString(), anyMap());
    }

    @Test
    void onNegotiationExpired_notifiesTraveler_evenWithNullSender() {
        UUID travelerId = UUID.randomUUID();
        var event = new NegotiationExpiredEvent(
            UUID.randomUUID(), UUID.randomUUID(), null, travelerId
        );

        listener.onNegotiationExpired(event);

        verify(dispatcher).notifyUser(eq(travelerId), anyString(), anyString(), anyMap());
        verifyNoMoreInteractions(dispatcher);
    }

    @Test
    void onNegotiationExpired_notifiesBothParties_whenSenderPresent() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        var event = new NegotiationExpiredEvent(
            UUID.randomUUID(), UUID.randomUUID(), senderId, travelerId
        );

        listener.onNegotiationExpired(event);

        verify(dispatcher).notifyUser(eq(travelerId), anyString(), anyString(), anyMap());
        verify(dispatcher).notifyUser(eq(senderId), anyString(), anyString(), anyMap());
    }

    @Test
    void onPackageRequestCreated_logsButDoesNotDispatch() {
        var event = new PackageRequestCreatedEvent(
            UUID.randomUUID(), UUID.randomUUID(),
            "Paris", "Dakar", LocalDate.now().plusDays(7)
        );

        listener.onPackageRequestCreated(event);

        verifyNoInteractions(dispatcher);
    }
}
