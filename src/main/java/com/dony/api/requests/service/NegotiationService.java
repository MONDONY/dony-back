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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NegotiationService {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(NegotiationService.class);


    private final PackageRequestRepository requestRepo;
    private final NegotiationThreadRepository threadRepo;
    private final NegotiationMessageRepository messageRepo;
    private final UserRepository userRepository;
    private final com.dony.api.matching.AnnouncementRepository announcementRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final RequestsConfig config;

    public NegotiationService(PackageRequestRepository requestRepo,
                               NegotiationThreadRepository threadRepo,
                               NegotiationMessageRepository messageRepo,
                               UserRepository userRepository,
                               com.dony.api.matching.AnnouncementRepository announcementRepo,
                               ApplicationEventPublisher eventPublisher,
                               AuditService auditService,
                               RequestsConfig config) {
        this.requestRepo = requestRepo;
        this.threadRepo = threadRepo;
        this.messageRepo = messageRepo;
        this.userRepository = userRepository;
        this.announcementRepo = announcementRepo;
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

        if (threadRepo.findActiveByPackageRequestIdAndTravelerId(req.packageRequestId(), travelerId).isPresent()) {
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

        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        return toResponse(saved, List.of(toMessageResponse(msg)), null, traveler, request, travelerId, senderName);
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
        UserEntity traveler = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        return toResponse(thread, responses, null, traveler, request, callerId, senderName);
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

    /**
     * Bilateral accept — both the sender AND the traveler can accept the other's counter-offer.
     * <ul>
     *   <li>If the traveler accepts AND already has a trip linked → AWAITING_PAYMENT (skip trip step).</li>
     *   <li>Otherwise → AWAITING_TRIP (traveler must still link / create a trip).</li>
     * </ul>
     */
    @Transactional
    public NegotiationThreadResponse accept(UUID callerId, UUID threadId, NegotiationAcceptRequest req) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // Participant check — sender OU traveler
        if (!callerId.equals(request.getSenderId()) && !callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }
        if (thread.getStatus() != NegotiationThreadStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/already-finalized");
        }

        // On ne peut pas accepter son propre message
        List<NegotiationMessageEntity> allMessages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId);
        if (allMessages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/inconsistent-thread");
        }
        NegotiationMessageEntity lastMsg = allMessages.get(allMessages.size() - 1);
        if (lastMsg.getFromUserId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/not-your-turn");
        }

        NegotiationMessageEntity acceptMsg = NegotiationMessageEntity.create(
            threadId, callerId, NegotiationMessageKind.ACCEPT, null,
            req == null ? null : req.body());
        messageRepo.save(acceptMsg);

        // Si c'est le voyageur qui accepte ET qu'il a déjà un trajet lié → AWAITING_PAYMENT direct
        boolean travelerIsAcceptor = callerId.equals(thread.getTravelerId());
        boolean travelerHasTrip = thread.getTravelerAnnouncementId() != null;
        NegotiationThreadStatus nextStatus = (travelerIsAcceptor && travelerHasTrip)
            ? NegotiationThreadStatus.AWAITING_PAYMENT
            : NegotiationThreadStatus.AWAITING_TRIP;

        thread.setStatus(nextStatus);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        String auditAction = nextStatus == NegotiationThreadStatus.AWAITING_PAYMENT
            ? "ACCEPT_AWAITING_PAYMENT" : "ACCEPT_AWAITING_TRIP";
        auditService.log("NEGOTIATION_THREAD", threadId, auditAction, callerId,
            Map.of("price", thread.getCurrentPriceEur().toString()));

        if (nextStatus == NegotiationThreadStatus.AWAITING_PAYMENT) {
            eventPublisher.publishEvent(new NegotiationAwaitingPaymentEvent(
                thread.getId(), request.getId(),
                request.getSenderId(), thread.getTravelerId(),
                thread.getCurrentPriceEur(), thread.getTravelerAnnouncementId()
            ));
        } else {
            eventPublisher.publishEvent(new NegotiationAwaitingTripEvent(
                thread.getId(), request.getId(),
                request.getSenderId(), thread.getTravelerId(),
                thread.getCurrentPriceEur()
            ));
        }

        List<NegotiationMessageResponse> responses = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        UserEntity traveler = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        return toResponse(thread, responses, null, traveler, request, callerId, senderName);
    }

    /**
     * Traveler links an existing announcement (or just-created one) to the accepted thread.
     * The announcement must belong to caller and match the package_request corridor + date window.
     * Thread moves to AWAITING_PAYMENT. Sender is notified to checkout.
     */
    @Transactional
    public NegotiationThreadResponse submitTrip(UUID callerId, UUID threadId, UUID travelerAnnouncementId) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        if (!callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-traveler");
        }
        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_TRIP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-awaiting-trip");
        }
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // Validate the announcement belongs to caller and matches corridor + date window
        com.dony.api.matching.AnnouncementEntity ann = announcementRepo.findById(travelerAnnouncementId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "announcement/not-found"));
        if (!ann.getTravelerId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "announcement/not-yours");
        }
        if (!ann.getDepartureCity().equalsIgnoreCase(request.getDepartureCity())
            || !ann.getArrivalCity().equalsIgnoreCase(request.getArrivalCity())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "announcement/corridor-mismatch");
        }
        java.time.LocalDate annDate = ann.getDepartureDate();
        java.time.LocalDate from = request.getDesiredDate().minusDays(request.getDateToleranceDays());
        java.time.LocalDate to = request.getDesiredDate().plusDays(request.getDateToleranceDays());
        if (annDate.isBefore(from) || annDate.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "announcement/date-mismatch");
        }

        thread.setTravelerAnnouncementId(travelerAnnouncementId);
        thread.setTravelerTravelDate(annDate);
        thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        eventPublisher.publishEvent(new NegotiationAwaitingPaymentEvent(
            thread.getId(), request.getId(),
            request.getSenderId(), thread.getTravelerId(),
            thread.getCurrentPriceEur(), travelerAnnouncementId
        ));
        auditService.log("NEGOTIATION_THREAD", threadId, "TRIP_LINKED", callerId,
            Map.of("announcementId", travelerAnnouncementId.toString()));

        List<NegotiationMessageResponse> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        UserEntity submitTraveler = userRepository.findById(callerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        return toResponse(thread, allMsgs, null, submitTraveler, request, callerId, senderName);
    }

    /**
     * Traveler creates a brand-new "dedicated trip" announcement that is linked
     * exclusively to this package_request. Used when none of the traveler's
     * existing trips match the corridor/date.
     *
     * Locked from the package_request: corridor, weightKg (= availableKg/totalKg),
     * transportMode, agreed price (= thread.currentPriceEur, stored as pricePerKg
     * = currentPrice / weightKg since the trip is private and never priced again).
     *
     * Editable by the traveler: departureDate (must fall in the tolerance window),
     * times, addresses, description, content type lists.
     *
     * On success the thread transitions AWAITING_TRIP → AWAITING_PAYMENT and the
     * sender is notified to checkout, exactly like {@link #submitTrip}.
     */
    @Transactional
    public NegotiationThreadResponse createDedicatedTrip(UUID callerId, UUID threadId,
                                                          NegotiationCreateDedicatedTripRequest req) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        if (!callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-traveler");
        }
        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_TRIP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-awaiting-trip");
        }

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // Validate the chosen date falls within the sender's tolerance window.
        java.time.LocalDate from = request.getDesiredDate().minusDays(request.getDateToleranceDays());
        java.time.LocalDate to = request.getDesiredDate().plusDays(request.getDateToleranceDays());
        if (req.departureDate().isBefore(from) || req.departureDate().isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "announcement/date-mismatch");
        }

        UserEntity traveler = userRepository.findById(callerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));

        // Build the dedicated announcement with all locked fields derived server-side.
        com.dony.api.matching.AnnouncementEntity ann = new com.dony.api.matching.AnnouncementEntity();
        ann.setTravelerId(callerId);
        ann.setTravelerIsPro(traveler.isProAccount());
        ann.setDepartureCity(request.getDepartureCity());
        ann.setArrivalCity(request.getArrivalCity());
        ann.setDepartureDate(req.departureDate());
        ann.setDepartureTime(req.departureTime());
        ann.setArrivalTime(req.arrivalTime());
        ann.setPickupAddressLabel(req.pickupAddress().label());
        ann.setPickupLat(BigDecimal.valueOf(req.pickupAddress().lat()));
        ann.setPickupLng(BigDecimal.valueOf(req.pickupAddress().lng()));
        ann.setDeliveryAddressLabel(req.deliveryAddress().label());
        ann.setDeliveryLat(BigDecimal.valueOf(req.deliveryAddress().lat()));
        ann.setDeliveryLng(BigDecimal.valueOf(req.deliveryAddress().lng()));
        ann.setAvailableKg(request.getWeightKg());
        ann.setTotalKg(request.getWeightKg());
        // Price-per-kg is derived from the agreed total. The trip is private, so
        // this value is never displayed — but it must be > 0 to satisfy the
        // pricePerKg >= 0.01 validation on the entity column.
        BigDecimal derivedPricePerKg = thread.getCurrentPriceEur()
            .divide(request.getWeightKg(), 2, RoundingMode.HALF_UP);
        if (derivedPricePerKg.signum() <= 0) {
            derivedPricePerKg = new BigDecimal("0.01");
        }
        ann.setPricePerKg(derivedPricePerKg);
        ann.setTransportMode(request.getTransportMode());
        ann.setStatus(com.dony.api.matching.AnnouncementStatus.ACTIVE);
        ann.setDescription(req.description());
        ann.setAcceptedContentTypes(req.acceptedContentTypes() != null ? req.acceptedContentTypes() : new ArrayList<>());
        ann.setRefusedTypes(req.refusedTypes() != null ? req.refusedTypes() : new ArrayList<>());
        ann.setLinkedPackageRequestId(request.getId());

        com.dony.api.matching.AnnouncementEntity savedAnn = announcementRepo.save(ann);

        // Link the dedicated trip to the thread and transition to AWAITING_PAYMENT.
        thread.setTravelerAnnouncementId(savedAnn.getId());
        thread.setTravelerTravelDate(savedAnn.getDepartureDate());
        thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        eventPublisher.publishEvent(new NegotiationAwaitingPaymentEvent(
            thread.getId(), request.getId(),
            request.getSenderId(), thread.getTravelerId(),
            thread.getCurrentPriceEur(), savedAnn.getId()
        ));
        auditService.log("NEGOTIATION_THREAD", threadId, "DEDICATED_TRIP_CREATED", callerId,
            Map.of("announcementId", savedAnn.getId().toString(),
                   "linkedPackageRequestId", request.getId().toString()));

        List<NegotiationMessageResponse> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        return toResponse(thread, allMsgs, null, traveler, request, callerId, senderName);
    }

    /**
     * Sender confirms payment for an AWAITING_PAYMENT thread. This finalizes:
     *  - thread → ACCEPTED
     *  - package_request → ACCEPTED
     *  - all competing OPEN threads on the same request → AUTO_REJECTED
     *  - payment_intent_id stored on thread
     *
     * Currently this is a synchronous placeholder — the real Stripe escrow call
     * is wired separately in {@code PaymentService.createNegotiationEscrow} (Phase 3).
     * Caller passes the paymentIntentId returned by Stripe (or a placeholder for now).
     */
    @Transactional
    public NegotiationThreadResponse finalizeAfterPayment(UUID callerId, UUID threadId, String paymentIntentId) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));
        if (!callerId.equals(request.getSenderId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }
        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-awaiting-payment");
        }

        thread.setPaymentIntentId(paymentIntentId);
        thread.setStatus(NegotiationThreadStatus.ACCEPTED);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        request.setStatus(PackageRequestStatus.ACCEPTED);
        requestRepo.save(request);

        threadRepo.findByPackageRequestId(request.getId()).stream()
            .filter(t -> !t.getId().equals(thread.getId())
                      && (t.getStatus() == NegotiationThreadStatus.OPEN
                          || t.getStatus() == NegotiationThreadStatus.AWAITING_TRIP
                          || t.getStatus() == NegotiationThreadStatus.AWAITING_PAYMENT))
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
            thread.getTravelerAnnouncementId(),
            request.getWeightKg(),
            request.getDescription(),
            request.getContentCategory(),
            paymentIntentId
        ));
        auditService.log("NEGOTIATION_THREAD", threadId, "ACCEPTED", callerId,
            Map.of("price", thread.getCurrentPriceEur().toString(),
                "paymentIntentId", paymentIntentId));
        auditService.log("PACKAGE_REQUEST", request.getId(), "ACCEPTED", callerId,
            Map.of("threadId", thread.getId().toString()));

        List<NegotiationMessageResponse> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        UserEntity finalTraveler = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        return toResponse(thread, allMsgs, paymentIntentId, finalTraveler, request, callerId, senderName);
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
        UserEntity threadTraveler = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        return toResponse(thread, messages, null, threadTraveler, request, callerId, senderName);
    }

    @Transactional(readOnly = true)
    public List<NegotiationThreadResponse> listMine(UUID userId) {
        return threadRepo.findByParticipant(userId).stream()
            .flatMap(t -> {
                var messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(t.getId())
                    .stream().map(this::toMessageResponse).toList();
                var travelerOpt = userRepository.findById(t.getTravelerId());
                var requestOpt = requestRepo.findById(t.getPackageRequestId());
                // Skip orphaned threads (soft-deleted request or unknown user) instead of failing the whole list
                if (travelerOpt.isEmpty() || requestOpt.isEmpty()) {
                    return java.util.stream.Stream.empty();
                }
                String senderName = userRepository.findById(requestOpt.get().getSenderId())
                    .map(this::buildDisplayName)
                    .orElse("Expéditeur");
                return java.util.stream.Stream.of(toResponse(t, messages, null, travelerOpt.get(), requestOpt.get(), userId, senderName));
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
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        return threadRepo.findByPackageRequestId(requestId).stream()
            .map(t -> {
                var messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(t.getId())
                    .stream().map(this::toMessageResponse).toList();
                UserEntity lt_traveler = userRepository.findById(t.getTravelerId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
                return toResponse(t, messages, null, lt_traveler, request, callerId, senderName);
            })
            .toList();
    }

    NegotiationThreadResponse toResponse(NegotiationThreadEntity t,
                                          List<NegotiationMessageResponse> messages,
                                          String paymentIntentClientSecret,
                                          UserEntity traveler,
                                          PackageRequestEntity request,
                                          UUID callerId,
                                          String senderName) {
        boolean isMyTurn = false;
        boolean canAccept = false;
        boolean canCounter = false;

        if (t.getStatus() == NegotiationThreadStatus.OPEN && callerId != null && !messages.isEmpty()) {
            NegotiationMessageResponse last = messages.get(messages.size() - 1);
            isMyTurn = !last.fromUserId().equals(callerId);
            boolean lastIsTarifaire = last.kind() == com.dony.api.requests.entity.NegotiationMessageKind.PROPOSAL
                || last.kind() == com.dony.api.requests.entity.NegotiationMessageKind.COUNTER;
            canAccept = isMyTurn && lastIsTarifaire;
            canCounter = isMyTurn && t.getRoundsCount() < config.maxNegotiationRounds();
        }
        int roundsRemaining = Math.max(0, config.maxNegotiationRounds() - t.getRoundsCount().intValue());

        return new NegotiationThreadResponse(
            t.getId(), t.getPackageRequestId(), t.getTravelerId(),
            t.getTravelerAnnouncementId(), t.getTravelerTravelDate(), t.getTravelerAvailableKg(),
            t.getStatus(), t.getCurrentPriceEur(), t.getRoundsCount().intValue(),
            t.getLastActivityAt(), t.getCreatedAt(),
            messages, paymentIntentClientSecret,
            buildDisplayName(traveler), traveler.getAverageRating(),
            traveler.getTotalTrips(), null,
            request.getDepartureCity(), request.getArrivalCity(), request.getWeightKg(),
            senderName,
            isMyTurn, canAccept, canCounter, roundsRemaining
        );
    }

    private String buildDisplayName(UserEntity user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        if (first != null && !first.isBlank()) {
            if (last != null && !last.isBlank()) {
                return first + " " + last.charAt(0) + ".";
            }
            return first;
        }
        return "Voyageur";
    }

    NegotiationMessageResponse toMessageResponse(NegotiationMessageEntity m) {
        return new NegotiationMessageResponse(
            m.getId(), m.getThreadId(), m.getFromUserId(),
            m.getKind(), m.getProposedPriceEur(), m.getBody(),
            m.getCreatedAt()
        );
    }
}
