package com.dony.api.cancellation.job;

import com.dony.api.admin.AdminAlertEntity;
import com.dony.api.admin.AdminAlertRepository;
import com.dony.api.cancellation.events.ReturnDeadlineExpiredEvent;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job J+3 (D4) : détecte les colis annulés après remise non rendus dans les 3 jours,
 * lève une alerte admin persistée {@code RETURN_DEADLINE_EXPIRED} et publie
 * {@link ReturnDeadlineExpiredEvent}. NE suspend JAMAIS automatiquement : l'admin décide
 * (voir {@code AdminUserController.suspendPublishing}). Idempotent via le payload de l'alerte.
 */
@Component
public class ReturnDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReturnDeadlineScheduler.class);
    static final String ALERT_TYPE = "RETURN_DEADLINE_EXPIRED";

    private final BidRepository bidRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReturnDeadlineScheduler(BidRepository bidRepository,
                                   AdminAlertRepository adminAlertRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.adminAlertRepository = adminAlertRepository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "${dony.cancellation.return-deadline-cron}", zone = "UTC")
    @Transactional
    public void run() {
        List<BidEntity> expired =
                bidRepository.findByReturnDeadlineBeforeAndReturnedAtIsNull(LocalDateTime.now());
        if (expired.isEmpty()) {
            return;
        }
        List<AdminAlertEntity> open = adminAlertRepository.findByTypeAndResolved(ALERT_TYPE, false);
        for (BidEntity bid : expired) {
            String bidIdStr = bid.getId().toString();
            boolean already = open.stream()
                    .anyMatch(a -> a.getPayload() != null && a.getPayload().contains(bidIdStr));
            if (already) {
                continue;
            }
            AdminAlertEntity alert = new AdminAlertEntity();
            alert.setType(ALERT_TYPE);
            alert.setPayload(String.format(
                    "{\"bidId\":\"%s\",\"senderId\":\"%s\",\"returnDeadline\":\"%s\"}",
                    bid.getId(), bid.getSenderId(), bid.getReturnDeadline()));
            alert.setResolved(false);
            adminAlertRepository.save(alert);

            eventPublisher.publishEvent(new ReturnDeadlineExpiredEvent(bid.getId()));
            log.warn("Return deadline expired for bid {} — admin alert raised", bidIdStr);
        }
    }
}
