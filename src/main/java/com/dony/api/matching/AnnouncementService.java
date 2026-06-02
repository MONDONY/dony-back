package com.dony.api.matching;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.StripeAccountStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserProStatusChangedEvent;
import com.dony.api.auth.UserRepository;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.payments.cash.exception.CommissionMethodMissingException;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.config.DonyConfigProperties;
import com.dony.api.matching.dto.AnnouncementDetailResponse;
import com.dony.api.matching.dto.AnnouncementRequest;
import com.dony.api.matching.dto.AnnouncementResponse;
import com.dony.api.matching.dto.AnnouncementSearchResponse;
import com.dony.api.matching.dto.TravelerProfileDto;
import com.dony.api.matching.events.AnnouncementDeletedEvent;
import com.dony.api.matching.events.AnnouncementInProgressEvent;
import com.dony.api.matching.events.BidExpiredOnDepartureEvent;
import com.dony.api.matching.AnnouncementPublishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Paris");

    private final AnnouncementRepository announcementRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final DonyConfigProperties config;
    private final PriceGridService priceGridService;

    @Value("${dony.kyc.enforce:true}")
    private boolean enforceKyc;

    @Value("${dony.stripe.enforce:true}")
    private boolean enforceStripeOnboarding;

    public AnnouncementService(
            AnnouncementRepository announcementRepository,
            BidRepository bidRepository,
            UserRepository userRepository,
            AuditService auditService,
            ApplicationEventPublisher eventPublisher,
            DonyConfigProperties config,
            PriceGridService priceGridService
    ) {
        this.announcementRepository = announcementRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.config = config;
        this.priceGridService = priceGridService;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "announcements-search", key = "#departureCity + '_' + #arrivalCity + '_' + #departureDateFrom + '_' + #departureDateTo + '_' + #minAvailableKg + '_' + #maxAvailableKg + '_' + #maxPricePerKg + '_' + #minRating + '_' + #kiloProOnly + '_' + #weekendOnly + '_' + #transportMode + '_' + #kycVerifiedOnly + '_' + #contentType + '_' + #userLat + '_' + #userLng + '_' + #radiusKm + '_' + #sortBy + '_' + #sortDir + '_' + #pageable.pageNumber + '_' + #viewerFirebaseUid")
    public Page<AnnouncementSearchResponse> searchAnnouncements(
            String departureCity, String arrivalCity,
            LocalDate departureDateFrom, LocalDate departureDateTo,
            BigDecimal minAvailableKg, BigDecimal maxAvailableKg,
            BigDecimal maxPricePerKg, BigDecimal minRating,
            Boolean kiloProOnly, Boolean weekendOnly,
            String transportMode, Boolean kycVerifiedOnly, String contentType,
            Double userLat, Double userLng, Double radiusKm,
            String sortBy, String sortDir, Pageable pageable,
            String viewerFirebaseUid) {

        // Confidentialité v2 — exclure (dans les deux sens) les voyageurs en relation
        // de blocage avec le viewer. Le firebaseUid est intégré à la clé de cache pour
        // éviter qu'un utilisateur reçoive les résultats filtrés d'un autre.
        UUID viewerId = (viewerFirebaseUid == null || viewerFirebaseUid.isBlank())
                ? null
                : userRepository.findByFirebaseUid(viewerFirebaseUid)
                        .map(UserEntity::getId)
                        .orElse(null);

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.hasStatus(AnnouncementStatus.ACTIVE)
                .and(AnnouncementSpecification.publicOnly());

        if (viewerId != null)
            spec = spec.and(AnnouncementSpecification.notBlockedBy(viewerId));

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
        if (maxAvailableKg != null)
            spec = spec.and(AnnouncementSpecification.maxAvailableKg(maxAvailableKg));
        if (maxPricePerKg != null)
            spec = spec.and(AnnouncementSpecification.maxPricePerKg(maxPricePerKg));
        if (Boolean.TRUE.equals(weekendOnly))
            spec = spec.and(AnnouncementSpecification.weekendOnly());
        if (minRating != null)
            spec = spec.and(AnnouncementSpecification.minRating(minRating));
        if (Boolean.TRUE.equals(kiloProOnly))
            spec = spec.and(AnnouncementSpecification.kiloProOnly());
        if (transportMode != null && !transportMode.isBlank()) {
            try {
                TransportMode mode = TransportMode.valueOf(transportMode.toUpperCase());
                spec = spec.and(AnnouncementSpecification.hasTransportMode(mode));
            } catch (IllegalArgumentException ignored) {
                // invalid enum value → ignore filter, don't crash
            }
        }
        if (Boolean.TRUE.equals(kycVerifiedOnly))
            spec = spec.and(AnnouncementSpecification.kycVerifiedOnly());
        if (contentType != null && !contentType.isBlank())
            spec = spec.and(AnnouncementSpecification.hasAcceptedContentType(contentType));

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
        Sort proFirst = Sort.by(Sort.Direction.DESC, "travelerIsPro");
        Sort secondary = switch (sortBy != null ? sortBy : "date") {
            case "price" -> Sort.by(direction, "pricePerKg");
            default -> Sort.by(direction, "departureDate");
        };
        return proFirst.and(secondary);
    }

    /**
     * Prix « affiché expéditeur » (net + commission Dony) pour le mode KG, symétrique de
     * {@code unitPriceDisplay} du mode MIXED. Source unique du multiplicateur :
     * {@link PriceGridService#displayPrice}. {@code null} si aucun prix au kilo (MIXED pur).
     */
    private static java.math.BigDecimal pricePerKgDisplay(java.math.BigDecimal net) {
        return net == null ? null : PriceGridService.displayPrice(net);
    }

    private AnnouncementSearchResponse toSearchResponse(AnnouncementEntity entity) {
        UserEntity traveler = userRepository.findById(entity.getTravelerId()).orElse(null);
        boolean kycVerified = traveler != null && traveler.getKycStatus() == KycStatus.VERIFIED;
        TravelerProfileDto profile = traveler != null
                ? new TravelerProfileDto(
                        traveler.getId(),
                        buildDisplayName(traveler),
                        traveler.getAverageRating() != null ? traveler.getAverageRating().doubleValue() : null,
                        traveler.getTotalTrips(),
                        traveler.isKiloPro(),
                        traveler.isProAccount(),
                        kycVerified)
                : null;
        long bidsCount = bidRepository.countVisibleByAnnouncementId(entity.getId());
        List<com.dony.api.matching.dto.AnnouncementPriceGridItemResponse> gridItems =
                entity.getPricingMode() == PricingMode.MIXED
                        ? priceGridService.getAnnouncementGridItems(entity.getId())
                        : List.of();
        return new AnnouncementSearchResponse(
                entity.getId(), entity.getTravelerId(),
                entity.getDepartureCity(), entity.getArrivalCity(),
                entity.getDepartureDate(),
                entity.getDepartureTime(), entity.getArrivalTime(),
                new com.dony.api.matching.dto.AddressDto(entity.getPickupAddressLabel(), entity.getPickupLat().doubleValue(), entity.getPickupLng().doubleValue()),
                new com.dony.api.matching.dto.AddressDto(entity.getDeliveryAddressLabel(), entity.getDeliveryLat().doubleValue(), entity.getDeliveryLng().doubleValue()),
                entity.getAvailableKg(), entity.getTotalKg(), entity.getPricePerKg(),
                pricePerKgDisplay(entity.getPricePerKg()),
                entity.getTransportMode(),
                entity.getStatus().name(), bidsCount, profile,
                entity.getDescription(),
                entity.getAcceptedContentTypes(),
                entity.getRefusedTypes(),
                entity.getAcceptedPaymentMethods().stream().map(Enum::name).toList(),
                entity.getCapacityUnit(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getPricingMode(),
                gridItems
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

        if (!user.isProAccount() && config.limits() != null) {
            YearMonth current = YearMonth.now();
            LocalDateTime from = current.atDay(1).atStartOfDay();
            LocalDateTime to = current.atEndOfMonth().atTime(23, 59, 59);
            long count = announcementRepository.countByTravelerIdAndCreatedAtBetween(user.getId(), from, to);
            if (count >= config.limits().monthlyAnnouncements()) {
                throw new DonyBusinessException(
                        HttpStatus.FORBIDDEN,
                        "pro-limit-reached",
                        "Monthly announcement limit reached",
                        "Vous avez atteint votre limite de " + config.limits().monthlyAnnouncements()
                                + " annonces ce mois-ci. Passez en PRO pour continuer."
                );
            }
        }

        if (enforceKyc && user.getKycStatus() != KycStatus.VERIFIED) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN,
                    "kyc-not-verified",
                    "KYC Not Verified",
                    "Vous devez compléter votre vérification d'identité pour effectuer cette action"
            );
        }

        if (enforceStripeOnboarding && user.getStripeAccountStatus() != StripeAccountStatus.ONBOARDING_COMPLETE) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN,
                    "stripe-onboarding-incomplete",
                    "Stripe Onboarding Incomplete",
                    "Vous devez compléter la configuration de votre compte bancaire pour publier un trajet"
            );
        }

        if (!user.getRoles().contains(Role.TRAVELER)) {
            user.getRoles().add(Role.TRAVELER);
            userRepository.save(user);
        }

        Set<PaymentMethod> paymentMethods = resolvePaymentMethods(request.acceptedPaymentMethods(), user);

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(user.getId());
        announcement.setTravelerIsPro(user.isProAccount());
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
        announcement.setTotalKg(request.availableKg());
        announcement.setPricePerKg(request.pricePerKg());
        announcement.setTransportMode(request.transportMode());
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setDescription(request.description());
        if (request.acceptedContentTypes() != null) announcement.setAcceptedContentTypes(request.acceptedContentTypes());
        if (request.refusedTypes() != null) announcement.setRefusedTypes(request.refusedTypes());
        announcement.setAcceptedPaymentMethods(paymentMethods);
        announcement.setCapacityUnit(
            request.capacityUnit() != null ? request.capacityUnit() : CapacityUnit.SUITCASE_23KG
        );
        PricingMode pricingMode = request.pricingMode() != null ? request.pricingMode() : PricingMode.KG;
        announcement.setPricingMode(pricingMode);
        if (pricingMode == PricingMode.KG &&
                (request.pricePerKg() == null || request.pricePerKg().compareTo(java.math.BigDecimal.ZERO) <= 0)) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-price",
                    "Prix invalide",
                    "Le prix par kg est obligatoire en mode KG"
            );
        }
        if (request.departureDate() != null && request.departureDate().isBefore(LocalDate.now())) {
            throw new DonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid-departure-date",
                "Date invalide",
                "La date de départ ne peut pas être dans le passé"
            );
        }

        AnnouncementEntity saved = announcementRepository.save(announcement);

        if (pricingMode == PricingMode.MIXED) {
            priceGridService.snapshotToAnnouncement(user.getId(), saved.getId());
        }

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

        eventPublisher.publishEvent(new com.dony.api.matching.events.AnnouncementCreatedEvent(
            saved.getId(),
            saved.getDepartureCity(),
            "",
            saved.getArrivalCity(),
            ""
        ));

        eventPublisher.publishEvent(new AnnouncementPublishedEvent(
            saved.getId(),
            saved.getTravelerId(),
            user.getFirstName() + " " + user.getLastName(),
            saved.getDepartureCity(),
            saved.getArrivalCity()
        ));

        return toResponse(saved);
    }

    @Transactional
    public Page<AnnouncementResponse> getMyAnnouncements(
            String firebaseUid, AnnouncementStatus statusFilter, String q,
            LocalDate date, LocalDate dateFrom, LocalDate dateTo,
            String departure, String arrival, Pageable pageable) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));

        // Transition inline: before returning the list, check if any ACTIVE/FULL
        // announcements have passed their departure time and update them immediately.
        // This makes the "En cours" status appear as soon as the traveler opens the screen,
        // without waiting for the hourly scheduler.
        triggerInProgressTransitions();

        String qParam         = (q         != null && !q.isBlank())         ? q.trim()         : null;
        String departureParam = (departure != null && !departure.isBlank()) ? departure.trim() : null;
        String arrivalParam   = (arrival   != null && !arrival.isBlank())   ? arrival.trim()   : null;
        Page<AnnouncementEntity> page = announcementRepository.findByTravelerIdFiltered(
                user.getId(), statusFilter, qParam, date, dateFrom, dateTo, departureParam, arrivalParam, pageable);
        return page.map(this::toResponse);
    }

    public List<com.dony.api.matching.dto.CorridorDto> getMyCorridors(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        return announcementRepository
                .findTopDestinationsForTraveler(user.getId(), PageRequest.of(0, 100))
                .stream()
                .map(d -> new com.dony.api.matching.dto.CorridorDto(d.from(), d.to()))
                .toList();
    }

    /**
     * Checks all ACTIVE/FULL announcements whose departure time has passed and transitions
     * them to IN_PROGRESS (or directly COMPLETED if no ACCEPTED bids remain).
     * Called inline on each "Mes trajets" load, and also by the hourly scheduler as a safety net.
     */
    public void triggerInProgressTransitions() {
        ZonedDateTime nowParis = ZonedDateTime.now(DEFAULT_ZONE);
        LocalDate today = nowParis.toLocalDate();
        LocalTime nowTime = nowParis.toLocalTime();

        List<AnnouncementEntity> candidates =
                announcementRepository.findDepartedActiveAnnouncements(today, nowTime);

        for (AnnouncementEntity announcement : candidates) {
            try {
                applyInProgressTransition(announcement);
            } catch (Exception e) {
                log.error("Inline transition failed for announcement {}: {}",
                        announcement.getId(), e.getMessage(), e);
            }
        }
    }

    private void applyInProgressTransition(AnnouncementEntity announcement) {
        AnnouncementStatus previous = announcement.getStatus();
        boolean hasAcceptedBids = bidRepository.existsByAnnouncementIdAndStatusIn(
                announcement.getId(), List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT));

        if (!hasAcceptedBids) {
            announcement.setStatus(AnnouncementStatus.COMPLETED);
            announcementRepository.save(announcement);
            auditService.log("ANNOUNCEMENT", announcement.getTravelerId(),
                    "ANNOUNCEMENT_COMPLETED", announcement.getId(),
                    Map.of("previousStatus", previous.name(), "trigger", "DEPARTURE_NO_ACCEPTED_BIDS"));
            log.info("Announcement {} → COMPLETED (no ACCEPTED bids at departure)", announcement.getId());
        } else {
            announcement.setStatus(AnnouncementStatus.IN_PROGRESS);
            announcementRepository.save(announcement);
            auditService.log("ANNOUNCEMENT", announcement.getTravelerId(),
                    "ANNOUNCEMENT_IN_PROGRESS", announcement.getId(),
                    Map.of("previousStatus", previous.name()));
            eventPublisher.publishEvent(
                    new AnnouncementInProgressEvent(announcement.getId(), announcement.getTravelerId()));
            log.info("Announcement {} → IN_PROGRESS", announcement.getId());
        }

        expirePendingBids(announcement);
    }

    private void expirePendingBids(AnnouncementEntity announcement) {
        List<BidEntity> pendingBids = bidRepository.findByAnnouncementIdAndStatusIn(
                announcement.getId(), List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED));
        for (BidEntity bid : pendingBids) {
            bid.setStatus(BidStatus.EXPIRED);
            bidRepository.save(bid);
            auditService.log("BID", bid.getId(), "BID_EXPIRED_ON_DEPARTURE",
                    announcement.getTravelerId(),
                    Map.of("announcementId", announcement.getId().toString()));
            eventPublisher.publishEvent(new BidExpiredOnDepartureEvent(
                    bid.getId(), bid.getSenderId(), announcement.getId(), announcement.getTravelerId()));
        }
    }

    @Transactional(readOnly = true)
    public AnnouncementDetailResponse getAnnouncementDetail(UUID id, String firebaseUid) {
        AnnouncementEntity announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        long bidsCount = bidRepository.countVisibleByAnnouncementId(id);

        UserEntity traveler = userRepository.findById(announcement.getTravelerId()).orElse(null);
        boolean kycVerified = traveler != null && traveler.getKycStatus() == KycStatus.VERIFIED;
        TravelerProfileDto travelerDto = traveler != null
                ? new TravelerProfileDto(
                        traveler.getId(),
                        buildDisplayName(traveler),
                        traveler.getAverageRating() != null ? traveler.getAverageRating().doubleValue() : null,
                        null,
                        traveler.isKiloPro(),
                        traveler.isProAccount(),
                        kycVerified)
                : null;

        List<com.dony.api.matching.dto.AnnouncementPriceGridItemResponse> gridItems =
                announcement.getPricingMode() == PricingMode.MIXED
                        ? priceGridService.getAnnouncementGridItems(announcement.getId())
                        : List.of();

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
                announcement.getTotalKg(),
                announcement.getPricePerKg(),
                pricePerKgDisplay(announcement.getPricePerKg()),
                announcement.getTransportMode(),
                announcement.getStatus().name(),
                bidsCount,
                travelerDto,
                announcement.getDescription(),
                announcement.getAcceptedContentTypes(),
                announcement.getRefusedTypes(),
                announcement.getAcceptedPaymentMethods().stream().map(Enum::name).toList(),
                announcement.getCapacityUnit(),
                announcement.getAcceptedPaymentMethods().contains(com.dony.api.payments.cash.PaymentMethod.CASH),
                announcement.getCreatedAt(),
                announcement.getUpdatedAt(),
                announcement.getPricingMode(),
                gridItems
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

        boolean hasAcceptedBids = bidRepository.existsByAnnouncementIdAndStatusIn(
                id, List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT));
        if (hasAcceptedBids) {
            throw new DonyBusinessException(
                    HttpStatus.CONFLICT,
                    "modification-impossible",
                    "Modification Impossible",
                    "Modification impossible : des colis sont déjà acceptés pour ce trajet"
            );
        }

        final com.dony.api.matching.TransportMode oldTransportMode = announcement.getTransportMode();

        PricingMode updatePricingMode = request.pricingMode() != null ? request.pricingMode() : announcement.getPricingMode();
        if (updatePricingMode == PricingMode.KG &&
                (request.pricePerKg() == null || request.pricePerKg().compareTo(java.math.BigDecimal.ZERO) <= 0)) {
            throw new DonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-price",
                    "Prix invalide",
                    "Le prix par kg est obligatoire en mode KG"
            );
        }

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
        // Update is blocked if any bid is ACCEPTED, so no booked weight to preserve → keep total in sync.
        announcement.setTotalKg(request.availableKg());
        announcement.setPricePerKg(request.pricePerKg());
        announcement.setTransportMode(request.transportMode());
        announcement.setDescription(request.description());
        if (request.acceptedContentTypes() != null) announcement.setAcceptedContentTypes(request.acceptedContentTypes());
        if (request.refusedTypes() != null) announcement.setRefusedTypes(request.refusedTypes());
        if (request.acceptedPaymentMethods() != null) {
            announcement.setAcceptedPaymentMethods(resolvePaymentMethods(request.acceptedPaymentMethods(), user));
        }
        if (request.capacityUnit() != null) {
            announcement.setCapacityUnit(request.capacityUnit());
        }

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

        boolean kycVerified = user.getKycStatus() == KycStatus.VERIFIED;
        TravelerProfileDto updatedTravelerDto = new TravelerProfileDto(
                user.getId(),
                buildDisplayName(user),
                null, null, false, user.isProAccount(), kycVerified);

        List<com.dony.api.matching.dto.AnnouncementPriceGridItemResponse> updatedGridItems =
                saved.getPricingMode() == PricingMode.MIXED
                        ? priceGridService.getAnnouncementGridItems(saved.getId())
                        : List.of();

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
                saved.getTotalKg(),
                saved.getPricePerKg(),
                pricePerKgDisplay(saved.getPricePerKg()),
                saved.getTransportMode(),
                saved.getStatus().name(),
                bidsCount,
                updatedTravelerDto,
                saved.getDescription(),
                saved.getAcceptedContentTypes(),
                saved.getRefusedTypes(),
                saved.getAcceptedPaymentMethods().stream().map(Enum::name).toList(),
                saved.getCapacityUnit(),
                saved.getAcceptedPaymentMethods().contains(com.dony.api.payments.cash.PaymentMethod.CASH),
                saved.getCreatedAt(),
                saved.getUpdatedAt(),
                saved.getPricingMode(),
                updatedGridItems
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

        if (bidRepository.existsByAnnouncementIdAndStatusIn(
                id, List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT))) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "deletion-impossible", "Deletion Impossible", "Suppression impossible : des colis sont déjà acceptés pour ce trajet");
        }

        List<BidEntity> pendingBids = bidRepository.findByAnnouncementIdAndStatusIn(
                id, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED));
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
        long pendingBidCount = bidRepository.countVisibleByAnnouncementId(entity.getId());
        long confirmedParcelCount = bidRepository.countByAnnouncementIdAndStatusIn(
                entity.getId(),
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.COMPLETED)
        );
        boolean cashAccepted = entity.getAcceptedPaymentMethods()
                .contains(com.dony.api.payments.cash.PaymentMethod.CASH);
        List<com.dony.api.matching.dto.AnnouncementPriceGridItemResponse> gridItems =
                entity.getPricingMode() == PricingMode.MIXED
                        ? priceGridService.getAnnouncementGridItems(entity.getId())
                        : List.of();
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
                entity.getTotalKg(),
                entity.getPricePerKg(),
                pricePerKgDisplay(entity.getPricePerKg()),
                entity.getTransportMode(),
                entity.getStatus().name(),
                pendingBidCount,
                confirmedParcelCount,
                entity.getDescription(),
                entity.getAcceptedContentTypes(),
                entity.getRefusedTypes(),
                entity.getAcceptedPaymentMethods().stream().map(Enum::name).toList(),
                entity.getCapacityUnit(),
                cashAccepted,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getPricingMode(),
                gridItems
        );
    }

    @Transactional(readOnly = true)
    public java.util.List<com.dony.api.matching.dto.TravelerAnnouncementResponse> getTravelerAnnouncements(java.util.UUID travelerId) {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 50,
            org.springframework.data.domain.Sort.by("departureDate").ascending());
        var active = announcementRepository.findByTravelerIdAndStatus(travelerId, AnnouncementStatus.ACTIVE, pageable).getContent();
        var full   = announcementRepository.findByTravelerIdAndStatus(travelerId, AnnouncementStatus.FULL, pageable).getContent();
        return java.util.stream.Stream.concat(active.stream(), full.stream())
            .map(a -> new com.dony.api.matching.dto.TravelerAnnouncementResponse(
                a.getId(), a.getDepartureCity(), a.getArrivalCity(),
                a.getDepartureDate(), a.getPricePerKg(), a.getAvailableKg(), a.getStatus().name()))
            .toList();
    }

    @EventListener
    @Transactional
    public void onUserProStatusChanged(UserProStatusChangedEvent event) {
        int updated = announcementRepository.updateTravelerProStatus(event.userId(), event.isPro());
        log.info("PRO status change for user {} (isPro={}) — {} open announcements updated",
                event.userId(), event.isPro(), updated);
    }

    private Set<PaymentMethod> resolvePaymentMethods(Set<PaymentMethod> requested, UserEntity traveler) {
        if (requested == null || requested.isEmpty()) {
            return EnumSet.of(PaymentMethod.STRIPE);
        }
        // La vérification de la capacité de paiement de la commission (wallet ou carte)
        // est reportée à l'acceptation du bid (CashCommissionService.acceptCashBid).
        // Un voyageur peut offrir le cash dès lors qu'il a un compte Dony,
        // même sans carte de commission enregistrée (le wallet prend en charge).
        return EnumSet.copyOf(requested);
    }
}
