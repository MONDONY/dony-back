package com.dony.api.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publie chaque jour les trajets récurrents dus dans la fenêtre d'horizon
 * de chaque récurrence active. Idempotent via TripRecurrence.lastGeneratedDate.
 */
@Component
public class TripRecurrenceScheduler {

    private static final Logger log = LoggerFactory.getLogger(TripRecurrenceScheduler.class);

    private final TripRecurrenceService service;

    public TripRecurrenceScheduler(TripRecurrenceService service) {
        this.service = service;
    }

    // Tous les jours à 03:00 UTC.
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void generateRecurringTrips() {
        log.debug("TripRecurrenceScheduler: running daily generation");
        service.generateDueTrips();
    }
}
