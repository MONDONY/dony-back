package com.dony.api.ratings;

import com.dony.api.ratings.events.RatingCreatedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class DeliveryConfirmedEventListener {

    private final BadgeService badgeService;
    private final FraudDetectionService fraudDetectionService;

    public DeliveryConfirmedEventListener(BadgeService badgeService,
                                          FraudDetectionService fraudDetectionService) {
        this.badgeService = badgeService;
        this.fraudDetectionService = fraudDetectionService;
    }

    // Story 9.3 — Trigger Kilo Pro check on each confirmed delivery
    @EventListener
    @Async
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        badgeService.checkAndGrantKiloPro(event.getTravelerId());
    }

    // Story 9.7 — Trigger farming detection after each new rating
    @EventListener
    @Async
    public void onRatingCreated(RatingCreatedEvent event) {
        if (event.getRaterId() != null) {
            fraudDetectionService.detectRatingFarming(event.getRatingId());
        }
    }
}
