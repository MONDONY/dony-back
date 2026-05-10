package com.dony.api.requests.service;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.requests.RequestsConfig;
import com.dony.api.requests.dto.*;
import com.dony.api.requests.entity.*;
import com.dony.api.requests.event.*;
import com.dony.api.requests.repository.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NegotiationService {

    private final PackageRequestRepository requestRepo;
    private final NegotiationThreadRepository threadRepo;
    private final NegotiationMessageRepository messageRepo;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final RequestsConfig config;

    public NegotiationService(PackageRequestRepository requestRepo,
                               NegotiationThreadRepository threadRepo,
                               NegotiationMessageRepository messageRepo,
                               UserRepository userRepository,
                               ApplicationEventPublisher eventPublisher,
                               AuditService auditService,
                               RequestsConfig config) {
        this.requestRepo = requestRepo;
        this.threadRepo = threadRepo;
        this.messageRepo = messageRepo;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.config = config;
    }

    @Transactional
    public NegotiationThreadResponse start(UUID travelerId, NegotiationStartRequest req) {
        UserEntity traveler = userRepository.findById(travelerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));

        if (traveler.getKycStatus() != KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "kyc/not-verified");
        }

        PackageRequestEntity request = requestRepo.findById(req.packageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        if (request.getSenderId().equals(travelerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/cannot-bid-own-request");
        }

        if (request.getStatus() == PackageRequestStatus.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.GONE, "request/expired");
        }

        if (request.getStatus() == PackageRequestStatus.ACCEPTED
                || request.getStatus() == PackageRequestStatus.COMPLETED
                || request.getStatus() == PackageRequestStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/already-finalized");
        }

        if (threadRepo.findByPackageRequestIdAndTravelerId(req.packageRequestId(), travelerId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/duplicate-thread");
        }

        long openCount = threadRepo.countByTravelerIdAndStatus(travelerId, NegotiationThreadStatus.OPEN);
        if (openCount >= config.maxOpenThreadsPerTraveler()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/max-open-reached");
        }

        long recent = threadRepo.countCreatedBy(travelerId, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        if (recent >= config.threadsPerMinuteRateLimit()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "negotiation/rate-limit");
        }

        NegotiationThreadEntity thread = new NegotiationThreadEntity();
        thread.setPackageRequestId(req.packageRequestId());
        thread.setTravelerId(travelerId);
        thread.setTravelerAnnouncementId(req.travelerAnnouncementId());
        thread.setTravelerTravelDate(req.travelerTravelDate());
        thread.setTravelerAvailableKg(req.travelerAvailableKg());
        thread.setStatus(NegotiationThreadStatus.OPEN);
        thread.setCurrentPriceEur(req.proposedPriceEur());
        thread.setRoundsCount((short) 1);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));

        NegotiationThreadEntity saved = threadRepo.save(thread);

        NegotiationMessageEntity msg = NegotiationMessageEntity.create(
            saved.getId(), travelerId, NegotiationMessageKind.PROPOSAL,
            req.proposedPriceEur(), req.body()
        );
        messageRepo.save(msg);

        if (request.getStatus() == PackageRequestStatus.OPEN) {
            request.setStatus(PackageRequestStatus.NEGOTIATING);
            requestRepo.save(request);
        }

        eventPublisher.publishEvent(new NegotiationStartedEvent(
            saved.getId(), saved.getPackageRequestId(),
            request.getSenderId(), travelerId, req.proposedPriceEur()
        ));

        auditService.log("NEGOTIATION_THREAD", saved.getId(), "CREATED", travelerId,
            Map.of("price", req.proposedPriceEur().toString()));

        return toResponse(saved, List.of(toMessageResponse(msg)), null);
    }

    NegotiationThreadResponse toResponse(NegotiationThreadEntity t,
                                          List<NegotiationMessageResponse> messages,
                                          String paymentIntentClientSecret) {
        return new NegotiationThreadResponse(
            t.getId(), t.getPackageRequestId(), t.getTravelerId(),
            t.getTravelerAnnouncementId(), t.getTravelerTravelDate(), t.getTravelerAvailableKg(),
            t.getStatus(), t.getCurrentPriceEur(), t.getRoundsCount().intValue(),
            t.getLastActivityAt(), t.getCreatedAt(),
            messages, paymentIntentClientSecret
        );
    }

    NegotiationMessageResponse toMessageResponse(NegotiationMessageEntity m) {
        return new NegotiationMessageResponse(
            m.getId(), m.getThreadId(), m.getFromUserId(),
            m.getKind(), m.getProposedPriceEur(), m.getBody(),
            m.getCreatedAt()
        );
    }
}
