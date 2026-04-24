package com.dony.api.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class MatchingScheduler {

    private static final Logger log = LoggerFactory.getLogger(MatchingScheduler.class);

    private final BidRepository bidRepository;
    private final BidService bidService;

    public MatchingScheduler(BidRepository bidRepository, BidService bidService) {
        this.bidRepository = bidRepository;
        this.bidService = bidService;
    }

    // Runs every 15 minutes — alerts senders when handover window is ≤ 2h away
    // and the traveler hasn't confirmed presence yet
    @Scheduled(fixedRate = 900_000)
    public void sendH2Alerts() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime threshold = now.plusHours(2);

        List<BidEntity> bids = bidRepository.findBidsNeedingH2Alert(now, threshold);
        for (BidEntity bid : bids) {
            try {
                // Mark alert sent first (idempotency — prevents duplicate alerts)
                bidService.markH2AlertSent(bid.getId());
                // TODO Epic 8: publish HandoverAlertEvent → NotificationDispatcher
                log.info("H-2 alert sent for bid {}", bid.getId());
            } catch (Exception e) {
                log.error("Failed to send H-2 alert for bid {}: {}", bid.getId(), e.getMessage());
            }
        }
    }
}
