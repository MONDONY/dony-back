package com.dony.api.requests.service;

import com.dony.api.common.AuditService;
import com.dony.api.requests.entity.NegotiationThreadEntity;
import com.dony.api.requests.entity.NegotiationThreadStatus;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.event.NegotiationExpiredEvent;
import com.dony.api.requests.event.PackageRequestExpiredEvent;
import com.dony.api.requests.repository.NegotiationThreadRepository;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Per-item expiry, each in its OWN transaction ({@code REQUIRES_NEW}). Split out of
 * {@link ExpirationScheduler} so one row's optimistic-lock conflict (e.g. an
 * {@code AWAITING_PAYMENT} thread finalized concurrently, now that
 * {@code NegotiationThreadEntity} carries {@code @Version}) cannot roll back the
 * whole expiry batch. Each method re-loads and re-checks status, so a concurrently
 * finalized/expired row is skipped idempotently rather than clobbered.
 *
 * <p>Must be a separate Spring bean (not a private method) so the {@code REQUIRES_NEW}
 * proxy actually applies — self-invocation would bypass it.
 */
@Component
public class NegotiationExpiryRunner {

    private final PackageRequestRepository requestRepo;
    private final NegotiationThreadRepository threadRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    public NegotiationExpiryRunner(PackageRequestRepository requestRepo,
                                   NegotiationThreadRepository threadRepo,
                                   ApplicationEventPublisher eventPublisher,
                                   AuditService auditService) {
        this.requestRepo = requestRepo;
        this.threadRepo = threadRepo;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireThread(UUID threadId, NegotiationThreadStatus expectedStatus, String reason) {
        NegotiationThreadEntity t = threadRepo.findById(threadId).orElse(null);
        // Re-check status: a thread finalized/expired between the scheduler's scan and
        // now must NOT be clobbered to EXPIRED — skip idempotently.
        if (t == null || t.getStatus() != expectedStatus) {
            return;
        }
        t.setStatus(NegotiationThreadStatus.EXPIRED);
        t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(t);
        eventPublisher.publishEvent(new NegotiationExpiredEvent(
            t.getId(), t.getPackageRequestId(), null, t.getTravelerId()));
        auditService.log("NEGOTIATION_THREAD", t.getId(), "EXPIRED", null,
            Map.of("source", "SYSTEM", "reason", reason));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireRequest(UUID requestId) {
        PackageRequestEntity req = requestRepo.findById(requestId).orElse(null);
        // findExpired matches OPEN/NEGOTIATING; re-check so a request that moved on
        // (ACCEPTED/CANCELLED/…) is skipped.
        if (req == null
                || (req.getStatus() != PackageRequestStatus.OPEN
                    && req.getStatus() != PackageRequestStatus.NEGOTIATING)) {
            return;
        }
        req.setStatus(PackageRequestStatus.EXPIRED);
        requestRepo.save(req);
        eventPublisher.publishEvent(new PackageRequestExpiredEvent(req.getId(), req.getSenderId()));
        auditService.log("PACKAGE_REQUEST", req.getId(), "EXPIRED", null,
            Map.of("source", "SYSTEM"));
    }
}
