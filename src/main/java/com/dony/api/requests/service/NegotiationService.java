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

    @Transactional
    public NegotiationThreadResponse counter(UUID callerId, UUID threadId, NegotiationCounterRequest req) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));

        if (thread.getStatus() != NegotiationThreadStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.GONE, "thread/expired");
        }

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        UUID senderId = request.getSenderId();
        UUID travelerId = thread.getTravelerId();
        if (!callerId.equals(senderId) && !callerId.equals(travelerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }

        if (thread.getRoundsCount() >= config.maxNegotiationRounds()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/max-rounds-reached");
        }

        List<NegotiationMessageEntity> messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId);
        if (messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/inconsistent-thread");
        }
        NegotiationMessageEntity lastMessage = messages.get(messages.size() - 1);
        if (lastMessage.getFromUserId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/not-your-turn");
        }

        NegotiationMessageEntity msg = NegotiationMessageEntity.create(
            threadId, callerId, NegotiationMessageKind.COUNTER, req.proposedPriceEur(), req.body());
        messageRepo.save(msg);

        thread.setCurrentPriceEur(req.proposedPriceEur());
        thread.setRoundsCount((short) (thread.getRoundsCount() + 1));
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        UUID toUser = callerId.equals(senderId) ? travelerId : senderId;
        eventPublisher.publishEvent(new NegotiationCounterPostedEvent(
            threadId, msg.getId(), callerId, toUser, req.proposedPriceEur(),
            thread.getRoundsCount().intValue()
        ));
        auditService.log("NEGOTIATION_THREAD", threadId, "COUNTER_POSTED", callerId,
            Map.of("price", req.proposedPriceEur().toString(),
                "round", String.valueOf(thread.getRoundsCount())));

        List<NegotiationMessageEntity> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId);
        List<NegotiationMessageResponse> responses = allMsgs.stream().map(this::toMessageResponse).toList();
        return toResponse(thread, responses, null);
    }

    @Transactional
    public void reject(UUID callerId, UUID threadId, NegotiationRejectRequest req) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));

        if (thread.getStatus() != NegotiationThreadStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/already-finalized");
        }

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        UUID senderId = request.getSenderId();
        UUID travelerId = thread.getTravelerId();
        if (!callerId.equals(senderId) && !callerId.equals(travelerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }

        NegotiationMessageEntity msg = NegotiationMessageEntity.create(
            threadId, callerId, NegotiationMessageKind.REJECT, null, req.reason());
        messageRepo.save(msg);

        thread.setStatus(NegotiationThreadStatus.REJECTED);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        auditService.log("NEGOTIATION_THREAD", threadId, "REJECTED", callerId,
            Map.of("reason", req.reason() != null ? req.reason() : ""));
    }

    @Transactional
    public NegotiationThreadResponse accept(UUID callerId, UUID threadId, NegotiationAcceptRequest req) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // Only the sender (request owner) can accept a thread
        if (!callerId.equals(request.getSenderId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }
        if (thread.getStatus() != NegotiationThreadStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/already-finalized");
        }

        // TODO(stripe-integration): replace placeholder client_secret with real PaymentIntent.create(...)
        //   after extending PaymentEntity with negotiation_thread_id (new migration V60).
        //   See plan task 16 step 3 + payments/PaymentService.createIntent for the Bid pattern.
        String placeholderClientSecret = "pi_pending_" + thread.getId() + "_secret_" + UUID.randomUUID();
        thread.setPaymentIntentId("pi_pending_" + thread.getId());

        // Persist ACCEPT message (append-only)
        NegotiationMessageEntity msg = NegotiationMessageEntity.create(
            threadId, callerId, NegotiationMessageKind.ACCEPT, null,
            req == null ? null : req.body());
        messageRepo.save(msg);

        // Promote thread + request atomically
        thread.setStatus(NegotiationThreadStatus.ACCEPTED);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        request.setStatus(PackageRequestStatus.ACCEPTED);
        requestRepo.save(request);

        // Auto-reject competing OPEN threads
        threadRepo.findByPackageRequestId(request.getId()).stream()
            .filter(t -> !t.getId().equals(thread.getId())
                      && t.getStatus() == NegotiationThreadStatus.OPEN)
            .forEach(t -> {
                t.setStatus(NegotiationThreadStatus.AUTO_REJECTED);
                t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
                threadRepo.save(t);
                auditService.log("NEGOTIATION_THREAD", t.getId(), "AUTO_REJECTED", callerId,
                    Map.of("reason", "competing-accepted",
                        "winningThreadId", thread.getId().toString()));
            });

        eventPublisher.publishEvent(new PackageRequestAcceptedEvent(
            thread.getId(), request.getId(), request.getSenderId(),
            thread.getTravelerId(), thread.getCurrentPriceEur(),
            thread.getTravelerAnnouncementId()
        ));
        auditService.log("NEGOTIATION_THREAD", threadId, "ACCEPTED", callerId,
            Map.of("price", thread.getCurrentPriceEur().toString()));
        auditService.log("PACKAGE_REQUEST", request.getId(), "ACCEPTED", callerId,
            Map.of("threadId", thread.getId().toString()));

        List<NegotiationMessageResponse> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        return toResponse(thread, allMsgs, placeholderClientSecret);
    }

    @Transactional(readOnly = true)
    public NegotiationThreadResponse getById(UUID callerId, UUID threadId) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        if (!callerId.equals(request.getSenderId()) && !callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }

        List<NegotiationMessageResponse> messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        return toResponse(thread, messages, null);
    }

    @Transactional(readOnly = true)
    public List<NegotiationThreadResponse> listMine(UUID userId) {
        return threadRepo.findByParticipant(userId).stream()
            .map(t -> {
                var messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(t.getId())
                    .stream().map(this::toMessageResponse).toList();
                return toResponse(t, messages, null);
            })
            .toList();
    }

    /**
     * All threads attached to a single package_request (sender's inbox view
     * for one of their requests). Caller must be the sender of the request
     * — ownership check is enforced.
     */
    @Transactional(readOnly = true)
    public List<NegotiationThreadResponse> listForRequest(UUID callerId, UUID requestId) {
        PackageRequestEntity request = requestRepo.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));
        if (!request.getSenderId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/forbidden");
        }
        return threadRepo.findByPackageRequestId(requestId).stream()
            .map(t -> {
                var messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(t.getId())
                    .stream().map(this::toMessageResponse).toList();
                return toResponse(t, messages, null);
            })
            .toList();
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
