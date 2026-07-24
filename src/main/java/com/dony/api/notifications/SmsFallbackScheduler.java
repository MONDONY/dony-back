package com.dony.api.notifications;

import com.dony.api.auth.FirebaseContactService;
import com.dony.api.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Runs every 30 seconds. Finds critical notifications older than 60s with no ACK
 * and sends an SMS fallback. Idempotent: smsSentAt is set to prevent double-sends.
 */
@Component
public class SmsFallbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmsFallbackScheduler.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final SmsService smsService;
    private final FirebaseContactService firebaseContact;

    public SmsFallbackScheduler(NotificationRepository notificationRepository,
                                UserRepository userRepository,
                                SmsService smsService,
                                FirebaseContactService firebaseContact) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.smsService = smsService;
        this.firebaseContact = firebaseContact;
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void processPendingFallbacks() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(60);
        var pending = notificationRepository.findPendingSmsFallbacks(cutoff);

        if (pending.isEmpty()) return;

        log.debug("[SmsFallback] Processing {} pending fallback(s)", pending.size());

        for (var notification : pending) {
            try {
                userRepository.findById(notification.getUserId()).ifPresentOrElse(user -> {
                    // Le numéro vit dans Firebase, pas en base (cache mémoire 5 min).
                    String phone = firebaseContact.getContact(user.getFirebaseUid()).phoneNumber();
                    if (phone != null && !phone.isBlank()) {
                        smsService.send(phone, buildSmsText(notification));
                        log.info("[SmsFallback] SMS sent for notificationId={} userId={}",
                                notification.getId(), notification.getUserId());
                    } else {
                        log.warn("[SmsFallback] No phone number for userId={}, skipping",
                                notification.getUserId());
                    }
                    // Mark as handled regardless (no phone = no retry)
                    notification.markSmsSent(LocalDateTime.now(ZoneOffset.UTC));
                }, () -> {
                    log.warn("[SmsFallback] User not found for notificationId={}", notification.getId());
                    notification.markSmsSent(LocalDateTime.now(ZoneOffset.UTC));
                });
            } catch (Exception e) {
                log.error("[SmsFallback] Error for notificationId={}: {}", notification.getId(), e.getMessage());
            }
        }
    }

    private String buildSmsText(NotificationEntity n) {
        return "[dony] " + n.getTitle() + (n.getBody() != null ? " — " + n.getBody() : "");
    }
}