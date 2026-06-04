package com.dony.api.requests.service;

import com.dony.api.common.AuditService;
import com.dony.api.requests.RequestsConfig;
import com.dony.api.requests.entity.NegotiationThreadEntity;
import com.dony.api.requests.entity.NegotiationThreadStatus;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.event.NegotiationExpiredEvent;
import com.dony.api.requests.event.PackageRequestExpiredEvent;
import com.dony.api.requests.repository.NegotiationThreadRepository;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Component
public class ExpirationScheduler {

    private final PackageRequestRepository requestRepo;
    private final NegotiationThreadRepository threadRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final RequestsConfig config;

    public ExpirationScheduler(PackageRequestRepository requestRepo,
                               NegotiationThreadRepository threadRepo,
                               ApplicationEventPublisher eventPublisher,
                               AuditService auditService,
                               RequestsConfig config) {
        this.requestRepo   = requestRepo;
        this.threadRepo    = threadRepo;
        this.eventPublisher = eventPublisher;
        this.auditService  = auditService;
        this.config        = config;
    }

    /**
     * Scheduled entry-point: expire both package requests and negotiation threads.
     * Cron expression is externalized in dony.requests.auto-expire-check-cron (default: every 15 min).
     * In test profile it is set to "-" (disabled) so this method is never triggered automatically.
     */
    @Scheduled(cron = "${dony.requests.auto-expire-check-cron}")
    @Transactional
    public void runExpiration() {
        expireRequests();
        expireThreads();
    }

    /**
     * Marks OPEN/NEGOTIATING PackageRequests whose desiredDate is in the past as EXPIRED,
     * saves them, publishes a PackageRequestExpiredEvent, and creates an audit entry.
     */
    void expireRequests() {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC);
        List<PackageRequestEntity> expired = requestRepo.findExpired(cutoff);
        for (PackageRequestEntity req : expired) {
            req.setStatus(PackageRequestStatus.EXPIRED);
            requestRepo.save(req);
            eventPublisher.publishEvent(new PackageRequestExpiredEvent(req.getId(), req.getSenderId()));
            auditService.log("PACKAGE_REQUEST", req.getId(), "EXPIRED", null,
                Map.of("source", "SYSTEM"));
        }
    }

    /**
     * Marks negotiation threads as EXPIRED based on three rules:
     * 1. OPEN threads with no activity since threadInactivityHours.
     * 2. AWAITING_TRIP threads that have exceeded their awaitingTripHours deadline.
     * 3. AWAITING_PAYMENT threads that have exceeded their awaitingPaymentHours deadline.
     */
    void expireThreads() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 1) OPEN inactif
        for (NegotiationThreadEntity t : threadRepo.findInactive(now.minusHours(config.threadInactivityHours()))) {
            expire(t, "INACTIVE_OPEN");
        }
        // 2) AWAITING_TRIP dépassé
        for (NegotiationThreadEntity t : threadRepo.findAwaitingTripExpired(now.minusHours(config.awaitingTripHours()))) {
            expire(t, "AWAITING_TRIP_TIMEOUT");
        }
        // 3) AWAITING_PAYMENT dépassé
        for (NegotiationThreadEntity t : threadRepo.findAwaitingPaymentExpired(now.minusHours(config.awaitingPaymentHours()))) {
            expire(t, "AWAITING_PAYMENT_TIMEOUT");
        }
    }

    private void expire(NegotiationThreadEntity t, String reason) {
        t.setStatus(NegotiationThreadStatus.EXPIRED);
        t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(t);
        eventPublisher.publishEvent(new NegotiationExpiredEvent(
            t.getId(), t.getPackageRequestId(), null, t.getTravelerId()));
        auditService.log("NEGOTIATION_THREAD", t.getId(), "EXPIRED", null,
            Map.of("source", "SYSTEM", "reason", reason));
    }
}
