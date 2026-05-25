package com.dony.api.subscriptions;

import com.dony.api.matching.AnnouncementPublishedEvent;
import com.dony.api.notifications.NotificationDispatcher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TravelerAvailabilityListener {

    private final TravelerSubscriptionRepository subscriptionRepository;
    private final NotificationDispatcher notificationDispatcher;

    public TravelerAvailabilityListener(TravelerSubscriptionRepository subscriptionRepository,
                                        NotificationDispatcher notificationDispatcher) {
        this.subscriptionRepository = subscriptionRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnnouncementPublished(AnnouncementPublishedEvent event) {
        List<UUID> senderIds = subscriptionRepository
            .findSenderIdsByTravelerId(event.travelerId());

        if (senderIds.isEmpty()) return;

        String title = "Votre voyageur " + event.travelerName() + " a publié un nouveau départ";
        String body  = event.departureCity() + " → " + event.arrivalCity() + " — Réservez en 1 tap !";
        Map<String, String> data = Map.of(
            "type", "TRAVELER_AVAILABLE",
            "travelerId", event.travelerId().toString()
        );

        senderIds.forEach(senderId ->
            notificationDispatcher.notifyUser(senderId, title, body, data)
        );
    }
}
