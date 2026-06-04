package com.dony.api.requests.service;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.payments.PriceBreakdown;
import com.dony.api.payments.cash.CommissionProperties;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.requests.CashGatePort;
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
    private final CommissionProperties commissionProperties;
    private final CashGatePort cashGatePort;

    public NegotiationService(PackageRequestRepository requestRepo,
                               NegotiationThreadRepository threadRepo,
                               NegotiationMessageRepository messageRepo,
                               UserRepository userRepository,
                               com.dony.api.matching.AnnouncementRepository announcementRepo,
                               ApplicationEventPublisher eventPublisher,
                               AuditService auditService,
                               RequestsConfig config,
                               CommissionProperties commissionProperties,
                               CashGatePort cashGatePort) {
        this.requestRepo = requestRepo;
        this.threadRepo = threadRepo;
        this.messageRepo = messageRepo;
        this.userRepository = userRepository;
        this.announcementRepo = announcementRepo;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.config = config;
        this.commissionProperties = commissionProperties;
        this.cashGatePort = cashGatePort;
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

        if (!request.isNegotiable()) {
            if (request.getTargetPriceEur() == null
                || req.proposedPriceEur().compareTo(request.getTargetPriceEur()) != 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "negotiation/firm-price-must-match");
            }
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

        boolean canOfferAny = request.getAcceptedPaymentMethods().stream()
            .anyMatch(m -> travelerCanOffer(traveler, m));
        if (!canOfferAny) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "payment-method/not-offerable");
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
        return toResponse(saved, List.of(toMessageResponse(msg)), null, traveler, request, travelerId, senderName, null);
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

        if (!request.isNegotiable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "negotiation/counter-not-allowed-firm-price");
        }

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
        return toResponse(thread, responses, null, traveler, request, callerId, senderName, null);
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
        com.dony.api.matching.AnnouncementEntity linkedAnn = thread.getTravelerAnnouncementId() != null
            ? announcementRepo.findById(thread.getTravelerAnnouncementId()).orElse(null)
            : null;
        return toResponse(thread, responses, null, traveler, request, callerId, senderName, linkedAnn);
    }

    /**
     * Traveler links an existing announcement (or just-created one) to the accepted thread.
     * The announcement must belong to caller and match the package_request corridor + date window.
     * Thread moves to AWAITING_PAYMENT. Sender is notified to checkout.
     */
    @Transactional
    public NegotiationThreadResponse submitTrip(UUID callerId, UUID threadId, NegotiationSubmitTripRequest req) {
        UUID travelerAnnouncementId = req.travelerAnnouncementId();
        PaymentMethod paymentMethod = req.paymentMethod();

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

        if (!request.getAcceptedPaymentMethods().contains(paymentMethod)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "payment-method/not-accepted-by-request");
        }

        // Validate the announcement belongs to caller and matches corridor + date window
        com.dony.api.matching.AnnouncementEntity ann = announcementRepo.findById(travelerAnnouncementId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "announcement/not-found"));
        if (!ann.getTravelerId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "announcement/not-yours");
        }
        // Normalize city names before comparison: "Paris, France" and "Paris" both
        // reduce to "paris". Legacy announcements were stored with country suffix.
        if (!cityKey(ann.getDepartureCity()).equals(cityKey(request.getDepartureCity()))
            || !cityKey(ann.getArrivalCity()).equals(cityKey(request.getArrivalCity()))) {
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

        if (paymentMethod == PaymentMethod.CASH) {
            BigDecimal commission = PriceBreakdown.fromNet(thread.getCurrentPriceEur(), commissionProperties.rate()).commission();
            if (!cashGatePort.hasSufficientFunds(callerId, commission)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "payment-method/traveler-insufficient-funds-cash");
            }
        }

        thread.setTravelerAnnouncementId(travelerAnnouncementId);
        thread.setTravelerTravelDate(annDate);
        thread.setPaymentMethod(paymentMethod);
        thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        eventPublisher.publishEvent(new NegotiationAwaitingPaymentEvent(
            thread.getId(), request.getId(),
            request.getSenderId(), thread.getTravelerId(),
            thread.getCurrentPriceEur(), travelerAnnouncementId
        ));
        auditService.log("NEGOTIATION_THREAD", threadId, "TRIP_LINKED", callerId,
            Map.of("announcementId", travelerAnnouncementId.toString(),
                   "paymentMethod", paymentMethod.name()));

        List<NegotiationMessageResponse> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        UserEntity submitTraveler = userRepository.findById(callerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        return toResponse(thread, allMsgs, null, submitTraveler, request, callerId, senderName, ann);
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

        if (!request.getAcceptedPaymentMethods().contains(req.paymentMethod())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "payment-method/not-accepted-by-request");
        }

        // Validate the chosen date falls within the sender's tolerance window.
        java.time.LocalDate from = request.getDesiredDate().minusDays(request.getDateToleranceDays());
        java.time.LocalDate to = request.getDesiredDate().plusDays(request.getDateToleranceDays());
        if (req.departureDate().isBefore(from) || req.departureDate().isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "announcement/date-mismatch");
        }

        if (req.paymentMethod() == PaymentMethod.CASH) {
            BigDecimal commission = PriceBreakdown.fromNet(thread.getCurrentPriceEur(), commissionProperties.rate()).commission();
            if (!cashGatePort.hasSufficientFunds(callerId, commission)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "payment-method/traveler-insufficient-funds-cash");
            }
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
        thread.setPaymentMethod(req.paymentMethod());
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
                   "linkedPackageRequestId", request.getId().toString(),
                   "paymentMethod", req.paymentMethod().name()));

        List<NegotiationMessageResponse> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        return toResponse(thread, allMsgs, null, traveler, request, callerId, senderName, savedAnn);
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

        if (request.getRecipientName() == null || request.getRecipientPhone() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "request/details-incomplete");
        }

        // CASH threads : prélever la commission Dony au voyageur (wallet puis carte) AVANT
        // de finaliser. En échec → 422 et la tx @Transactional rollback : le thread reste
        // AWAITING_PAYMENT (non finalisé). STRIPE : pas de prélèvement ici (application_fee
        // déjà géré par PaymentService.createNegotiationEscrow).
        if (thread.getPaymentMethod() == PaymentMethod.CASH) {
            boolean charged = cashGatePort.chargeNegotiationCashCommission(
                thread.getTravelerId(), request.getSenderId(), thread.getId(), thread.getCurrentPriceEur());
            if (!charged) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "negotiation/commission-charge-failed");
            }
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
            paymentIntentId,
            request.getRecipientName(),
            request.getRecipientPhone(),
            request.getDeclaredValueEur(),
            request.getDisclaimerSignedAt(),
            request.getDisclaimerSignedIp()
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
        com.dony.api.matching.AnnouncementEntity linkedAnn = thread.getTravelerAnnouncementId() != null
            ? announcementRepo.findById(thread.getTravelerAnnouncementId()).orElse(null)
            : null;
        return toResponse(thread, allMsgs, paymentIntentId, finalTraveler, request, callerId, senderName, linkedAnn);
    }

    @Transactional
    public NegotiationThreadResponse refuseTrip(UUID callerId, UUID threadId, String reason) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // Seul l'expéditeur peut refuser un trajet lié
        if (!callerId.equals(request.getSenderId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/sender-only");
        }

        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-awaiting-payment");
        }

        if (thread.getTravelerAnnouncementId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/no-trip-linked");
        }

        // Effacer le trajet lié et repasser en AWAITING_TRIP
        thread.setTravelerAnnouncementId(null);
        thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        // Persister la raison du refus comme message visible dans le thread
        if (reason != null && !reason.isBlank()) {
            NegotiationMessageEntity refusalMsg = new NegotiationMessageEntity();
            refusalMsg.setThreadId(threadId);
            refusalMsg.setFromUserId(callerId);
            refusalMsg.setKind(NegotiationMessageKind.REJECT);
            refusalMsg.setBody(reason);
            messageRepo.save(refusalMsg);
        }

        auditService.log("NEGOTIATION_THREAD", threadId, "TRIP_REFUSED", callerId,
            Map.of("reason", reason != null ? reason : "sender-refused"));

        // Notifier le voyageur via l'event existant NegotiationAwaitingTripEvent
        eventPublisher.publishEvent(new NegotiationAwaitingTripEvent(
            thread.getId(), request.getId(),
            request.getSenderId(), thread.getTravelerId(),
            thread.getCurrentPriceEur()
        ));

        List<NegotiationMessageResponse> messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        UserEntity traveler = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse("Expéditeur");
        // linkedAnn est null car on vient de clear le travelerAnnouncementId
        return toResponse(thread, messages, null, traveler, request, callerId, senderName, null);
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
        com.dony.api.matching.AnnouncementEntity linkedAnn = thread.getTravelerAnnouncementId() != null
            ? announcementRepo.findById(thread.getTravelerAnnouncementId()).orElse(null)
            : null;
        return toResponse(thread, messages, null, threadTraveler, request, callerId, senderName, linkedAnn);
    }

    @Transactional(readOnly = true)
    public List<NegotiationThreadResponse> listMine(UUID userId) {
        List<NegotiationThreadEntity> threads = threadRepo.findByParticipant(userId);

        // Batch-load announcements to avoid N+1
        List<UUID> announcementIds = threads.stream()
            .map(NegotiationThreadEntity::getTravelerAnnouncementId)
            .filter(java.util.Objects::nonNull)
            .toList();
        Map<UUID, com.dony.api.matching.AnnouncementEntity> annMap =
            announcementRepo.findAllById(announcementIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                    com.dony.api.matching.AnnouncementEntity::getId, a -> a));

        return threads.stream()
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
                com.dony.api.matching.AnnouncementEntity linkedAnn = t.getTravelerAnnouncementId() != null
                    ? annMap.get(t.getTravelerAnnouncementId())
                    : null;
                return java.util.stream.Stream.of(toResponse(t, messages, null, travelerOpt.get(), requestOpt.get(), userId, senderName, linkedAnn));
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
        var threads = threadRepo.findByPackageRequestId(requestId);
        // Batch-load announcements pour éviter N+1
        List<UUID> announcementIds = threads.stream()
            .map(t -> t.getTravelerAnnouncementId())
            .filter(java.util.Objects::nonNull)
            .toList();
        Map<UUID, com.dony.api.matching.AnnouncementEntity> annMap = announcementRepo.findAllById(announcementIds).stream()
            .collect(java.util.stream.Collectors.toMap(
                com.dony.api.matching.AnnouncementEntity::getId, a -> a));
        return threads.stream()
            .map(t -> {
                var messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(t.getId())
                    .stream().map(this::toMessageResponse).toList();
                UserEntity lt_traveler = userRepository.findById(t.getTravelerId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
                com.dony.api.matching.AnnouncementEntity linkedAnn = t.getTravelerAnnouncementId() != null
                    ? annMap.get(t.getTravelerAnnouncementId())
                    : null;
                return toResponse(t, messages, null, lt_traveler, request, callerId, senderName, linkedAnn);
            })
            .toList();
    }

    NegotiationThreadResponse toResponse(NegotiationThreadEntity t,
                                          List<NegotiationMessageResponse> messages,
                                          String paymentIntentClientSecret,
                                          UserEntity traveler,
                                          PackageRequestEntity request,
                                          UUID callerId,
                                          String senderName,
                                          com.dony.api.matching.AnnouncementEntity linkedAnn) {
        boolean isMyTurn = false;
        boolean canAccept = false;
        boolean canCounter = false;

        if (t.getStatus() == NegotiationThreadStatus.OPEN && callerId != null && !messages.isEmpty()) {
            NegotiationMessageResponse last = messages.get(messages.size() - 1);
            isMyTurn = !last.fromUserId().equals(callerId);
            boolean lastIsTarifaire = last.kind() == com.dony.api.requests.entity.NegotiationMessageKind.PROPOSAL
                || last.kind() == com.dony.api.requests.entity.NegotiationMessageKind.COUNTER;
            canAccept = isMyTurn && lastIsTarifaire;
            canCounter = isMyTurn && t.getRoundsCount() < config.maxNegotiationRounds()
                         && request.isNegotiable();
        }
        int roundsRemaining = Math.max(0, config.maxNegotiationRounds() - t.getRoundsCount().intValue());

        com.dony.api.requests.dto.LinkedTripSummary linkedTrip = null;
        if (linkedAnn != null) {
            linkedTrip = new com.dony.api.requests.dto.LinkedTripSummary(
                linkedAnn.getId(),
                linkedAnn.getDepartureCity(),
                linkedAnn.getArrivalCity(),
                linkedAnn.getDepartureDate() != null ? linkedAnn.getDepartureDate().toString() : null,
                linkedAnn.getDepartureTime() != null ? linkedAnn.getDepartureTime().toString() : null,
                linkedAnn.getTransportMode() != null ? linkedAnn.getTransportMode().name() : null,
                linkedAnn.getPickupAddressLabel(),
                linkedAnn.getDeliveryAddressLabel(),
                linkedAnn.getAvailableKg() != null ? linkedAnn.getAvailableKg().intValue() : 0,
                linkedAnn.getDescription()
            );
        }

        BigDecimal gross = t.getCurrentPriceEur() != null
            ? PriceBreakdown.fromNet(t.getCurrentPriceEur(), commissionProperties.rate()).gross()
            : null;

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
            isMyTurn, canAccept, canCounter, roundsRemaining,
            linkedTrip,
            gross,
            t.getPaymentMethod()
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

    /** Normalizes a city string for comparison by keeping only the part before
     *  the first comma and lowercasing. "Paris, France" → "paris". */
    private static String cityKey(String city) {
        if (city == null) return "";
        int comma = city.indexOf(',');
        return (comma >= 0 ? city.substring(0, comma) : city).strip().toLowerCase();
    }

    /**
     * Returns {@code true} if the traveler is technically capable of offering
     * the given payment method.
     * <ul>
     *   <li>STRIPE requires a fully onboarded Stripe Connect account.</li>
     *   <li>CASH / WAVE / ORANGE_MONEY are always available.</li>
     * </ul>
     */
    private boolean travelerCanOffer(UserEntity t, PaymentMethod m) {
        return switch (m) {
            case STRIPE -> t.getStripeAccountStatus() == StripeAccountStatus.ONBOARDING_COMPLETE;
            case CASH, WAVE, ORANGE_MONEY -> true;
        };
    }
}
