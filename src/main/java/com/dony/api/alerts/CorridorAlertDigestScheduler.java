package com.dony.api.alerts;

import com.dony.api.common.MatchingTextUtil;
import com.dony.api.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Component
public class CorridorAlertDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(CorridorAlertDigestScheduler.class);

    private final CorridorAlertRepository alertRepository;
    private final AlertService alertService;
    private final NotificationDispatcher notificationDispatcher;

    public CorridorAlertDigestScheduler(CorridorAlertRepository alertRepository,
                                        AlertService alertService,
                                        NotificationDispatcher notificationDispatcher) {
        this.alertRepository = alertRepository;
        this.alertService = alertService;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Scheduled(cron = "${app.alerts.digest-cron:0 0 9 * * *}", zone = "Europe/Paris")
    @Transactional
    public void runDigest() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<CorridorAlertEntity> active = alertRepository.findAllByActiveTrue();

        if (active.isEmpty()) {
            return;
        }
        log.debug("[CorridorAlertDigest] processing {} active alert(s)", active.size());

        for (CorridorAlertEntity alert : active) {
            try {
                LocalDateTime since = alert.getLastNotifiedAt() != null
                        ? alert.getLastNotifiedAt()
                        : now.minusHours(24);

                boolean isTrips = alert.getDirection() == AlertDirection.SENDER_WANTS_TRIPS;
                int count = isTrips
                        ? alertService.findRecentTripMatches(alert, since).size()
                        : alertService.findRecentMatches(alert, since).size();
                if (count == 0) {
                    continue;
                }

                String corridor = MatchingTextUtil.corridorLabel(alert.getDepartureCity(), alert.getArrivalCity());
                String title = isTrips ? "Nouveaux trajets sur " + corridor
                                       : "Nouveaux colis sur " + corridor;
                String body = isTrips ? count + " trajets correspondent à votre alerte"
                                      : count + " colis correspondent à votre alerte";
                Map<String, String> data = Map.of(
                        "type", "CORRIDOR_ALERT",
                        "alertId", alert.getId().toString(),
                        "corridor", corridor,
                        "direction", alert.getDirection().name());

                notificationDispatcher.notifyUser(alert.getOwnerId(), title, body, data);

                alert.setLastNotifiedAt(now);
                alertRepository.save(alert);
            } catch (Exception e) {
                log.error("[CorridorAlertDigest] error for alertId={}: {}",
                        alert.getId(), e.getMessage());
            }
        }
    }
}
