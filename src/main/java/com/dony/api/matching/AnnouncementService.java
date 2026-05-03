package com.dony.api.matching;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.AnnouncementDetailResponse;
import com.dony.api.matching.dto.AnnouncementRequest;
import com.dony.api.matching.dto.AnnouncementResponse;
import com.dony.api.matching.dto.AnnouncementSearchResponse;
import com.dony.api.matching.dto.TravelerProfileDto;
import com.dony.api.matching.events.AnnouncementDeletedEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public AnnouncementService(
            AnnouncementRepository announcementRepository,
            BidRepository bidRepository,
            UserRepository userRepository,
            AuditService auditService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "announcements-search", key = "#departureCity + '_' + #arrivalCity + '_' + #departureDateFrom + '_' + #departureDateTo + '_' + #minAvailableKg + '_' + #userLat + '_' + #userLng + '_' + #radiusKm + '_' + #sortBy + '_' + #sortDir + '_' + #pageable.pageNumber")
    public Page<AnnouncementSearchResponse> searchAnnouncements(
            String departureCity, String arrivalCity,
            LocalDate departureDateFrom, LocalDate departureDateTo,
            BigDecimal minAvailableKg,
            Double userLat, Double userLng, Double radiusKm,
            String sortBy, String sortDir, Pageable pageable) {

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.hasStatus(AnnouncementStatus.ACTIVE);

        if (departureCity != null && !departureCity.isBlank())
            spec = spec.and(AnnouncementSpecification.hasDepartureCity(departureCity));
        if (arrivalCity != null && !arrivalCity.isBlank())
            spec = spec.and(AnnouncementSpecification.hasArrivalCity(arrivalCity));
        if (departureDateFrom != null)
            spec = spec.and(AnnouncementSpecification.departureDateFrom(departureDateFrom));
        if (departureDateTo != null)
            spec = spec.and(AnnouncementSpecification.departureDateTo(departureDateTo));
        if (minAvailableKg != null)
            spec = spec.and(AnnouncementSpecification.minAvailableKg(minAvailableKg));

        // Radius filter: only active when ALL 3 params provided
        if (userLat != null && userLng != null && radiusKm != null && radiusKm > 0) {
            List<UUID> idsInRadius = announcementRepository.findIdsWithinPickupRadius(
                            userLat, userLng, radiusKm)
                    .stream()
                    .map(UUID::fromString)
                    .toList();
            spec = spec
                    .and(AnnouncementSpecification.hasPickupCoordinates())
                    .and(AnnouncementSpecification.idIn(idsInRadius));
        }

        Sort sort = buildSort(sortBy, sortDir);
        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);

        return announcementRepository.findAll(spec, sortedPageable)
                .map(this::toSearchResponse);
    }

    private Sort buildSort(String sortBy, String sortDir) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return switch (sortBy != null ? sortBy : "date") {
            case "price" -> Sort.by(direction, "pricePerKg");
            default -> Sort.by(direction, "departureDate");
        };
    }

    private AnnouncementSearchResponse toSearchResponse(AnnouncementEntity entity) {
        UserEntity traveler = userRepository.findById(entity.getTravelerId()).orElse(null);
        TravelerProfileDto profile = traveler != null
                ? new TravelerProfileDto(
                        traveler.getId(),
                        buildDisplayName(traveler),
                        traveler.getPhoneNumber(),
                        traveler.getAverageRating() != null ? traveler.getAverageRating().doubleValue() : null,
                        traveler.getTotalTrips(),
                        traveler.isKiloPro())
                : null;
        long bidsCount = bidRepository.countVisibleByAnnouncementId(entity.getId());
        return new AnnouncementSearchResponse(
                entity.getId(), entity.getTravelerId(),
                entity.getDepartureCity(), entity.getArrivalCity(),
                entity.getDepartureDate(),
                entity.getDepartureTime(), entity.getArrivalTime(),
                new com.dony.api.matching.dto.AddressDto(entity.getPickupAddressLabel(), entity.getPickupLat().doubleValue(), entity.getPickupLng().doubleValue()),
                new com.dony.api.matching.dto.AddressDto(entity.getDeliveryAddressLabel(), entity.getDeliveryLat().doubleValue(), entity.getDeliveryLng().doubleValue()),
                entity.getAvailableKg(), entity.getPricePerKg(),
                entity.getTransportMode(),
                entity.getStatus().name(), bidsCount, profile,
                entity.getDescription(),
                entity.getAcceptedContentTypes(),
                entity.getRefusedTypes(),
                entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }

    private String buildDisplayName(UserEntity user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        if (first != null && !first.isBlank() && last != null && !last.isBlank())
            return first.trim() + " " + last.trim();
        if (first != null && !first.isBlank()) return first.trim();
        if (last != null && !last.isBlank()) return last.trim();
        return null;
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public AnnouncementResponse createAnnouncement(String firebaseUid, AnnouncementRequest request) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"
                ));

        // TODO: Réactiver la vérification KYC avant la prod
        // if (user.getKycStatus() != KycStatus.VERIFIED) {
        //     throw new DonyBusinessException(
        //             HttpStatus.FORBIDDEN,
        //             "kyc-not-verified",
        //             "KYC Not Verified",
        //             "Vérifiez votre identité pour publier un trajet"
        //     );
        // }

        if (!user.getRoles().contains(Role.TRAVELER)) {
            user.getRoles().add(Role.TRAVELER);
            userRepository.save(user);
        }

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(user.getId());
        announcement.setDepartureCity(request.departureCity());
        announcement.setArrivalCity(request.arrivalCity());
        announcement.setDepartureDate(request.departureDate());
        announcement.setDepartureTime(request.departureTime());
        announcement.setArrivalTime(request.arrivalTime());
        announcement.setPickupAddressLabel(request.pickupAddress().label());
        announcement.setPickupLat(java.math.BigDecimal.valueOf(request.pickupAddress().lat()));
        announcement.setPickupLng(java.math.BigDecimal.valueOf(request.pickupAddress().lng()));
        announcement.setDeliveryAddressLabel(request.deliveryAddress().label());
        announcement.setDeliveryLat(java.math.BigDecimal.valueOf(request.deliveryAddress().lat()));
        announcement.setDeliveryLng(java.math.BigDecimal.valueOf(request.deliveryAddress().lng()));
        announcement.setAvailableKg(request.availableKg());
        announcement.setPricePerKg(request.pricePerKg());
        announcement.setTransportMode(request.transportMode());
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setDescription(request.description());
        if (request.acceptedContentTypes() != null) announcement.setAcceptedContentTypes(request.acceptedContentTypes());
        if (request.refusedTypes() != null) announcement.setRefusedTypes(request.refusedTypes());

        AnnouncementEntity saved = announcementRepository.save(announcement);

        auditService.log(
                "USER",
                user.getId(),
                "ANNOUNCEMENT_CREATED",
                saved.getId(),
                Map.of(
                        "departureCity", saved.getDepartureCity(),
                        "arrivalCity", saved.getArrivalCity(),
                        "departureDate", saved.getDepartureDate().toString(),
                        "availableKg", saved.getAvailableKg().toString(),
                        "pricePerKg", saved.getPricePerKg().toString(),
                        "transportMode", saved.getTransportMode().name()
                )
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<AnnouncementResponse> getMyAnnouncements(String firebaseUid, Pageable pageable) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        
        return announcementRepository.findByTravelerId(user.getId(), pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AnnouncementDetailResponse getAnnouncementDetail(UUID id, String firebaseUid) {
        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        long bidsCount = bidRepository.countVisibleByAnnouncementId(id);

        UserEntity traveler = userRepository.findById(announcement.getTravelerId()).orElse(null);
        TravelerProfileDto travelerDto = traveler != null
                ? new TravelerProfileDto(
                        traveler.getId(),
                        buildDisplayName(traveler),
                        traveler.getPhoneNumber(),
                        traveler.getAverageRating() != null ? traveler.getAverageRating().doubleValue() : null,
                        null,
                        traveler.isKiloPro())
                : null;

        return new AnnouncementDetailResponse(
                announcement.getId(),
                announcement.getTravelerId(),
                announcement.getDepartureCity(),
                announcement.getArrivalCity(),
                announcement.getDepartureDate(),
                announcement.getDepartureTime(),
                announcement.getArrivalTime(),
                new com.dony.api.matching.dto.AddressDto(announcement.getPickupAddressLabel(), announcement.getPickupLat().doubleValue(), announcement.getPickupLng().doubleValue()),
                new com.dony.api.matching.dto.AddressDto(announcement.getDeliveryAddressLabel(), announcement.getDeliveryLat().doubleValue(), announcement.getDeliveryLng().doubleValue()),
                announcement.getAvailableKg(),
                announcement.getPricePerKg(),
                announcement.getTransportMode(),
                announcement.getStatus().name(),
                bidsCount,
                travelerDto,
                announcement.getDescription(),
                announcement.getAcceptedContentTypes(),
                announcement.getRefusedTypes(),
                announcement.getCreatedAt(),
                announcement.getUpdatedAt()
        );
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public AnnouncementDetailResponse updateAnnouncement(UUID id, String firebaseUid, AnnouncementRequest request) {
        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));
        
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Vous n'êtes pas autorisé à modifier cette annonce");
        }

        boolean hasAcceptedBids = bidRepository.existsByAnnouncementIdAndStatus(id, BidStatus.ACCEPTED);
        if (hasAcceptedBids) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "modification-impossible",
                    "Modification Impossible",
                    "Modification impossible : des colis sont déjà acceptés pour ce trajet"
            );
        }

        final com.dony.api.matching.TransportMode oldTransportMode = announcement.getTransportMode();

        announcement.setDepartureCity(request.departureCity());
        announcement.setArrivalCity(request.arrivalCity());
        announcement.setDepartureDate(request.departureDate());
        announcement.setDepartureTime(request.departureTime());
        announcement.setArrivalTime(request.arrivalTime());
        announcement.setPickupAddressLabel(request.pickupAddress().label());
        announcement.setPickupLat(java.math.BigDecimal.valueOf(request.pickupAddress().lat()));
        announcement.setPickupLng(java.math.BigDecimal.valueOf(request.pickupAddress().lng()));
        announcement.setDeliveryAddressLabel(request.deliveryAddress().label());
        announcement.setDeliveryLat(java.math.BigDecimal.valueOf(request.deliveryAddress().lat()));
        announcement.setDeliveryLng(java.math.BigDecimal.valueOf(request.deliveryAddress().lng()));
        announcement.setAvailableKg(request.availableKg());
        announcement.setPricePerKg(request.pricePerKg());
        announcement.setTransportMode(request.transportMode());
        announcement.setDescription(request.description());
        if (request.acceptedContentTypes() != null) announcement.setAcceptedContentTypes(request.acceptedContentTypes());
        if (request.refusedTypes() != null) announcement.setRefusedTypes(request.refusedTypes());

        AnnouncementEntity saved = announcementRepository.save(announcement);

        auditService.log(
                "USER",
                user.getId(),
                "ANNOUNCEMENT_UPDATED",
                saved.getId(),
                Map.of(
                        "departureCity", saved.getDepartureCity(),
                        "arrivalCity", saved.getArrivalCity(),
                        "departureDate", saved.getDepartureDate().toString(),
                        "availableKg", saved.getAvailableKg().toString(),
                        "pricePerKg", saved.getPricePerKg().toString(),
                        "transportMode_old", oldTransportMode.name(),
                        "transportMode_new", saved.getTransportMode().name()
                )
        );

        long bidsCount = bidRepository.countVisibleByAnnouncementId(id);

        TravelerProfileDto updatedTravelerDto = new TravelerProfileDto(
                user.getId(),
                buildDisplayName(user),
                user.getPhoneNumber(),
                null, null, false);

        return new AnnouncementDetailResponse(
                saved.getId(),
                saved.getTravelerId(),
                saved.getDepartureCity(),
                saved.getArrivalCity(),
                saved.getDepartureDate(),
                saved.getDepartureTime(),
                saved.getArrivalTime(),
                new com.dony.api.matching.dto.AddressDto(saved.getPickupAddressLabel(), saved.getPickupLat().doubleValue(), saved.getPickupLng().doubleValue()),
                new com.dony.api.matching.dto.AddressDto(saved.getDeliveryAddressLabel(), saved.getDeliveryLat().doubleValue(), saved.getDeliveryLng().doubleValue()),
                saved.getAvailableKg(),
                saved.getPricePerKg(),
                saved.getTransportMode(),
                saved.getStatus().name(),
                bidsCount,
                updatedTravelerDto,
                saved.getDescription(),
                saved.getAcceptedContentTypes(),
                saved.getRefusedTypes(),
                saved.getCreatedAt(),
                saved.getUpdatedAt()
        );
    }

    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public void deleteAnnouncement(UUID id, String firebaseUid) {
        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));

        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Vous n'êtes pas autorisé à supprimer cette annonce");
        }

        if (announcement.getStatus() == AnnouncementStatus.CANCELLED) {
            // Soft-delete all associated bids (already CANCELLED from the cancellation flow)
            List<BidEntity> bids = bidRepository.findByAnnouncementId(id);
            for (BidEntity bid : bids) {
                bid.softDelete();
                bidRepository.save(bid);
            }

            announcement.softDelete();
            announcementRepository.save(announcement);

            // Notify cancellation package to clean up rematch suggestions
            eventPublisher.publishEvent(new AnnouncementDeletedEvent(id, user.getId()));

            auditService.log("ANNOUNCEMENT", user.getId(), "CANCELLED_ANNOUNCEMENT_DELETED", id,
                    Map.of("departureCity", announcement.getDepartureCity(),
                            "arrivalCity", announcement.getArrivalCity(),
                            "deletedBidsCount", String.valueOf(bids.size())));
            return;
        }

        if (announcement.getStatus() != AnnouncementStatus.ACTIVE) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "deletion-impossible", "Deletion Impossible",
                    "Seuls les trajets actifs ou annulés peuvent être supprimés");
        }

        if (bidRepository.existsByAnnouncementIdAndStatus(id, BidStatus.ACCEPTED)) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "deletion-impossible", "Deletion Impossible", "Suppression impossible : des colis sont déjà acceptés pour ce trajet");
        }

        List<BidEntity> pendingBids = bidRepository.findByAnnouncementIdAndStatus(id, BidStatus.PENDING);
        for (BidEntity bid : pendingBids) {
            bid.setStatus(BidStatus.REJECTED);
            bidRepository.save(bid);
            auditService.log("BID", bid.getId(), "BID_REJECTED_ANNOUNCEMENT_DELETED", user.getId(),
                    Map.of("announcementId", id.toString(), "senderId", bid.getSenderId().toString()));
        }

        announcement.softDelete();
        announcementRepository.save(announcement);

        auditService.log("ANNOUNCEMENT", user.getId(), "ANNOUNCEMENT_DELETED", id,
                Map.of("departureCity", announcement.getDepartureCity(),
                        "arrivalCity", announcement.getArrivalCity(),
                        "rejectedBidsCount", String.valueOf(pendingBids.size())));
    }

    private AnnouncementResponse toResponse(AnnouncementEntity entity) {
        long bidsCount = bidRepository.countVisibleByAnnouncementId(entity.getId());
        return new AnnouncementResponse(
                entity.getId(),
                entity.getTravelerId(),
                entity.getDepartureCity(),
                entity.getArrivalCity(),
                entity.getDepartureDate(),
                entity.getDepartureTime(),
                entity.getArrivalTime(),
                new com.dony.api.matching.dto.AddressDto(entity.getPickupAddressLabel(), entity.getPickupLat().doubleValue(), entity.getPickupLng().doubleValue()),
                new com.dony.api.matching.dto.AddressDto(entity.getDeliveryAddressLabel(), entity.getDeliveryLat().doubleValue(), entity.getDeliveryLng().doubleValue()),
                entity.getAvailableKg(),
                entity.getPricePerKg(),
                entity.getTransportMode(),
                entity.getStatus().name(),
                bidsCount,
                entity.getDescription(),
                entity.getAcceptedContentTypes(),
                entity.getRefusedTypes(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
