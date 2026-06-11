package com.dony.api.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

// Story 9.6 — Détection et sanction des no-shows voyageur
@Component
public class NoShowScheduler {

    private static final Logger log = LoggerFactory.getLogger(NoShowScheduler.class);
    private static final int NO_SHOW_GRACE_HOURS = 1;

    private final BidRepository bidRepository;
    private final NoShowService noShowService;

    public NoShowScheduler(BidRepository bidRepository,
                           NoShowService noShowService) {
        this.bidRepository = bidRepository;
        this.noShowService = noShowService;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC") // every hour, UTC
    @Transactional
    public void detectNoShows() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(NO_SHOW_GRACE_HOURS);
        List<BidEntity> noShowBids = bidRepository.findNoShowBids(cutoff);

        log.debug("No-show scheduler: {} bids to process", noShowBids.size());

        for (BidEntity bid : noShowBids) {
            try {
                noShowService.recordTravelerNoShow(bid.getId(), "scheduler");
            } catch (Exception e) {
                log.error("Error processing no-show for bid {}: {}", bid.getId(), e.getMessage(), e);
            }
        }
    }
}
