package com.dony.api.city;

import com.dony.api.matching.events.AnnouncementCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AnnouncementCreatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementCreatedEventListener.class);

    private final CorridorService corridorService;

    public AnnouncementCreatedEventListener(CorridorService corridorService) {
        this.corridorService = corridorService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onAnnouncementCreated(AnnouncementCreatedEvent event) {
        try {
            corridorService.upsertCorridor(
                event.departureCity(),
                event.departureCountry(),
                event.arrivalCity(),
                event.arrivalCountry()
            );
        } catch (Exception e) {
            log.warn("[CorridorAutoSave] Échec upsert corridor {} → {}: {}",
                event.departureCity(), event.arrivalCity(), e.getMessage());
        }
    }
}
