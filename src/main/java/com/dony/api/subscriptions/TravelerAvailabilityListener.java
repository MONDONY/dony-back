package com.dony.api.subscriptions;

import com.dony.api.matching.AnnouncementPublishedEvent;
import com.dony.api.notifications.NotificationDispatcher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;
import java.util.Map;

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
    @Transactional
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnnouncementPublished(AnnouncementPublishedEvent event) {
        List<TravelerSubscriptionEntity> subs =
            subscriptionRepository.findAllByTravelerId(event.travelerId());
        if (subs.isEmpty()) return;

        String title = event.travelerName() + " a publié un nouveau trajet";
        String body  = event.departureCity() + " → " + event.arrivalCity();
        Map<String, String> data = Map.of(
            "type", "TRAVELER_NEW_ANNOUNCEMENT",
            "announcementId", event.announcementId().toString(),
            "travelerId", event.travelerId().toString()
        );

        for (TravelerSubscriptionEntity sub : subs) {
            sub.setHasNew(true);
            subscriptionRepository.save(sub);
            notificationDispatcher.notifyUser(sub.getSenderId(), title, body, data, sub.isPushEnabled());
        }
    }
}
