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

    public PackageRequestService(PackageRequestRepository repository,
                                  UserRepository userRepository,
                                  ApplicationEventPublisher eventPublisher,
                                  AuditService auditService,
                                  RequestsConfig config,
                                  NegotiationThreadRepository threadRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.config = config;
        this.threadRepository = threadRepository;
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

    // ─── Mapper ─────────────────────────────────────────────────────────────────

    PackageRequestResponse toResponse(PackageRequestEntity e) {
        return new PackageRequestResponse(
            e.getId(), e.getSenderId(),
            e.getDepartureCity(), e.getArrivalCity(),
            e.getDesiredDate(), e.getDateToleranceDays() != null ? e.getDateToleranceDays().intValue() : 0,
            e.getWeightKg(), e.getParcelSize(), e.getContentCategory(),
            e.getDescription(), e.getTargetPriceEur(), e.getPhotoUrl(),
            e.getPickupNeighborhood(), e.getDeliveryNeighborhood(),
            e.getStatus(), e.getCreatedAt()
        );
    }
}
