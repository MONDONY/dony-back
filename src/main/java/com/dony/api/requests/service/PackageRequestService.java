package com.dony.api.requests.service;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.requests.RequestsConfig;
import com.dony.api.requests.dto.*;
import com.dony.api.requests.entity.*;
import com.dony.api.requests.event.*;
import com.dony.api.requests.repository.NegotiationThreadRepository;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PackageRequestService {

    private final PackageRequestRepository repository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final RequestsConfig config;
    private final NegotiationThreadRepository threadRepository;
    private final com.dony.api.city.CityRepository cityRepository;

    public PackageRequestService(PackageRequestRepository repository,
                                  UserRepository userRepository,
                                  ApplicationEventPublisher eventPublisher,
                                  AuditService auditService,
                                  RequestsConfig config,
                                  NegotiationThreadRepository threadRepository,
                                  com.dony.api.city.CityRepository cityRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.config = config;
        this.threadRepository = threadRepository;
        this.cityRepository = cityRepository;
    }

    // ─── create ─────────────────────────────────────────────────────────────────

    @Transactional
    public PackageRequestResponse create(UUID senderId, PackageRequestCreateRequest req) {
        UserEntity sender = userRepository.findById(senderId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));

        if (sender.getKycStatus() != KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "kyc/not-verified");
        }
        if (req.departureCity().equalsIgnoreCase(req.arrivalCity())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/invalid-corridor");
        }
        if (req.desiredDate().isAfter(LocalDate.now().plusDays(90))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/date-too-far");
        }
        long openCount = repository.countBySenderIdAndStatusIn(senderId,
            List.of(PackageRequestStatus.OPEN, PackageRequestStatus.NEGOTIATING));
        if (openCount >= config.maxOpenRequestsPerSender()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/max-open-reached");
        }

        PackageRequestEntity entity = new PackageRequestEntity();
        entity.setSenderId(senderId);
        entity.setDepartureCity(req.departureCity());
        entity.setArrivalCity(req.arrivalCity());
        entity.setDesiredDate(req.desiredDate());
        entity.setDateToleranceDays((short) req.dateToleranceDays());
        entity.setWeightKg(req.weightKg());
        entity.setParcelSize(req.parcelSize());
        entity.setTransportMode(req.transportMode());
        entity.setContentCategory(req.contentCategory());
        entity.setDescription(req.description());
        entity.setTargetPriceEur(req.targetPriceEur());
        entity.setPhotoUrl(req.photoUrl());
        entity.setPickupNeighborhood(req.pickupNeighborhood());
        entity.setDeliveryNeighborhood(req.deliveryNeighborhood());
        entity.setStatus(PackageRequestStatus.OPEN);

        PackageRequestEntity saved = repository.save(entity);

        eventPublisher.publishEvent(new PackageRequestCreatedEvent(
            saved.getId(), senderId, saved.getDepartureCity(),
            saved.getArrivalCity(), saved.getDesiredDate()
        ));
        auditService.log("PACKAGE_REQUEST", saved.getId(), "CREATED", senderId,
            Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));

        return toResponse(saved);
    }

    // ─── getById ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PackageRequestResponse getById(UUID callerUid, UUID requestId) {
        PackageRequestEntity entity = repository.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        boolean isOwner = entity.getSenderId().equals(callerUid);
        boolean isThreadParticipant = threadRepository
            .existsByPackageRequestIdAndTravelerId(requestId, callerUid);

        if (!isOwner && !isThreadParticipant) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/forbidden");
        }
        return toResponse(entity);
    }

    // ─── findMine ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<PackageRequestResponse> findMine(UUID senderId, Pageable pageable) {
        return repository.findBySenderIdOrderByCreatedAtDesc(senderId, pageable)
            .map(this::toResponse);
    }

    // ─── cancel ──────────────────────────────────────────────────────────────────

    @Transactional
    public void cancel(UUID callerUid, UUID requestId) {
        PackageRequestEntity entity = repository.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        if (!entity.getSenderId().equals(callerUid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/forbidden");
        }
        if (entity.getStatus() == PackageRequestStatus.ACCEPTED ||
            entity.getStatus() == PackageRequestStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/already-accepted");
        }

        entity.setStatus(PackageRequestStatus.CANCELLED);
        entity.softDelete();
        repository.save(entity);

        // Auto-reject any open threads
        threadRepository.findByPackageRequestId(requestId).forEach(t -> {
            if (t.getStatus() == NegotiationThreadStatus.OPEN) {
                t.setStatus(NegotiationThreadStatus.AUTO_REJECTED);
                threadRepository.save(t);
            }
        });

        auditService.log("PACKAGE_REQUEST", requestId, "CANCELLED", callerUid,
            Map.of("status", "CANCELLED"));
    }

    // ─── completeDetails ─────────────────────────────────────────────────────────

    @Transactional
    public PackageRequestResponse completeDetails(UUID callerUid, UUID requestId,
                                                   PackageRequestCompleteDetailsRequest req,
                                                   String clientIp) {
        PackageRequestEntity entity = repository.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        if (!entity.getSenderId().equals(callerUid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/forbidden");
        }
        if (entity.getStatus() != PackageRequestStatus.ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-yet-accepted");
        }
        if (!req.disclaimerSigned()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/disclaimer-not-signed");
        }

        entity.setPickupAddressLabel(req.pickupAddressLabel());
        entity.setPickupLat(req.pickupLat());
        entity.setPickupLng(req.pickupLng());
        entity.setDeliveryAddressLabel(req.deliveryAddressLabel());
        entity.setDeliveryLat(req.deliveryLat());
        entity.setDeliveryLng(req.deliveryLng());
        entity.setRecipientName(req.recipientName());
        entity.setRecipientPhone(req.recipientPhone());
        entity.setDeclaredValueEur(req.declaredValueEur());
        entity.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setDisclaimerSignedIp(clientIp);

        PackageRequestEntity saved = repository.save(entity);

        auditService.log("PACKAGE_REQUEST", requestId, "DETAILS_COMPLETED", callerUid,
            Map.of("recipient", req.recipientName(),
                   "declaredValue", req.declaredValueEur().toString()));

        // Propagate to the marketplace-issued bid (if any) via the matching/
        // listener so "Mes envois" stays in sync with the package_request data.
        threadRepository.findByPackageRequestId(requestId).stream()
            .filter(t -> t.getStatus() == com.dony.api.requests.entity.NegotiationThreadStatus.ACCEPTED
                      || t.getStatus() == com.dony.api.requests.entity.NegotiationThreadStatus.AWAITING_PAYMENT)
            .findFirst()
            .ifPresent(thread -> eventPublisher.publishEvent(
                new com.dony.api.requests.event.PackageRequestDetailsCompletedEvent(
                    requestId,
                    thread.getId(),
                    callerUid,
                    req.recipientName(),
                    req.recipientPhone(),
                    req.declaredValueEur(),
                    saved.getDisclaimerSignedAt(),
                    saved.getDisclaimerSignedIp()
                )));

        return toResponse(saved);
    }

    // ─── search ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<PackageRequestSearchResponse> search(Specification<PackageRequestEntity> spec,
                                                      Pageable pageable) {
        return repository.findAll(spec, pageable).map(this::toSearchResponse);
    }

    // ─── Mappers ─────────────────────────────────────────────────────────────────

    PackageRequestResponse toResponse(PackageRequestEntity e) {
        return new PackageRequestResponse(
            e.getId(), e.getSenderId(),
            e.getDepartureCity(), e.getArrivalCity(),
            e.getDesiredDate(), e.getDateToleranceDays() != null ? e.getDateToleranceDays().intValue() : 0,
            e.getWeightKg(), e.getParcelSize(), e.getTransportMode(),
            e.getContentCategory(),
            e.getDescription(), e.getTargetPriceEur(), e.getPhotoUrl(),
            e.getPickupNeighborhood(), e.getDeliveryNeighborhood(),
            e.getStatus(), e.getCreatedAt()
        );
    }

    private PackageRequestSearchResponse toSearchResponse(PackageRequestEntity e) {
        // Sender profile will be wired with UserService in a later task (Task 20+).
        var senderProfile = new PackageRequestSearchResponse.SenderPublicProfile(
            e.getSenderId(), "Sender", 0.0, 0, false
        );
        var depCity = cityRepository.findFirstByNameIgnoreCase(e.getDepartureCity()).orElse(null);
        var arrCity = cityRepository.findFirstByNameIgnoreCase(e.getArrivalCity()).orElse(null);
        return new PackageRequestSearchResponse(
            e.getId(), e.getDepartureCity(), e.getArrivalCity(),
            depCity != null ? depCity.getLatitude() : null,
            depCity != null ? depCity.getLongitude() : null,
            arrCity != null ? arrCity.getLatitude() : null,
            arrCity != null ? arrCity.getLongitude() : null,
            e.getDesiredDate(), e.getDateToleranceDays() != null ? e.getDateToleranceDays().intValue() : 0,
            e.getWeightKg(), e.getParcelSize(), e.getTransportMode(),
            e.getContentCategory(),
            e.getTargetPriceEur(), e.getPhotoUrl(),
            e.getPickupNeighborhood(), e.getDeliveryNeighborhood(),
            senderProfile
        );
    }
}
