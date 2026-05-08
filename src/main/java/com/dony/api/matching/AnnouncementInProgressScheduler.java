package com.dony.api.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety-net scheduler: runs hourly to catch announcements whose departure time
 * has passed for travelers who haven't opened the app recently.
 *
 * The primary transition path is inline in AnnouncementService.getMyAnnouncements()
 * which fires on every "Mes trajets" screen load — this gives real-time UX.
 */
@Component
public class AnnouncementInProgressScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementInProgressScheduler.class);

    private final AnnouncementService announcementService;

    public AnnouncementInProgressScheduler(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void processInProgressTransitions() {
        log.debug("AnnouncementInProgressScheduler (safety net): running");
        announcementService.triggerInProgressTransitions();
    }
}
