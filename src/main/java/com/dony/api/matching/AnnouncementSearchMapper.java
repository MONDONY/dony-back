package com.dony.api.matching;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.StorageService;
import com.dony.api.matching.dto.AddressDto;
import com.dony.api.matching.dto.AnnouncementPriceGridItemResponse;
import com.dony.api.matching.dto.AnnouncementSearchResponse;
import com.dony.api.matching.dto.TravelerProfileDto;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stateless mapper: converts an {@link AnnouncementEntity} to an {@link AnnouncementSearchResponse}.
 * Extracted from {@link AnnouncementService#toSearchResponse} so that packages outside
 * {@code matching/} (e.g. {@code favorites/}) can map announcement entities without
 * importing the full {@code AnnouncementService} and creating a cross-package service cycle.
 *
 * <p>No business logic lives here — only field projection and helper calls.
 */
@Component
public class AnnouncementSearchMapper {

    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final PriceGridService priceGridService;
    private final StorageService storageService;

    public AnnouncementSearchMapper(UserRepository userRepository,
                                    BidRepository bidRepository,
                                    PriceGridService priceGridService,
                                    StorageService storageService) {
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.priceGridService = priceGridService;
        this.storageService = storageService;
    }

    /**
     * Maps an entity to the search DTO.
     *
     * @param entity     the announcement entity (must not be null)
     * @param isFavorite whether the viewing user has favorited this trip
     * @return the populated search response record
     */
    public AnnouncementSearchResponse toSearchResponse(AnnouncementEntity entity, boolean isFavorite) {
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
                        kycVerified,
                        storageService.avatarUrl(traveler.getAvatarUrl()))
                : null;
        long bidsCount = bidRepository.countVisibleByAnnouncementId(entity.getId());
        List<AnnouncementPriceGridItemResponse> gridItems =
                entity.getPricingMode() == PricingMode.MIXED
                        ? priceGridService.getAnnouncementGridItems(entity.getId(), entity.getTravelerId())
                        : List.of();
        return new AnnouncementSearchResponse(
                entity.getId(), entity.getTravelerId(),
                entity.getDepartureCity(), entity.getArrivalCity(),
                entity.getDepartureDate(),
                entity.getDepartureTime(), entity.getArrivalTime(),
                new AddressDto(entity.getPickupAddressLabel(),
                        entity.getPickupLat().doubleValue(), entity.getPickupLng().doubleValue()),
                new AddressDto(entity.getDeliveryAddressLabel(),
                        entity.getDeliveryLat().doubleValue(), entity.getDeliveryLng().doubleValue()),
                entity.getAvailableKg(), entity.getTotalKg(), entity.getPricePerKg(),
                pricePerKgDisplay(entity.getPricePerKg(), entity.getTravelerId()),
                entity.getTransportMode(),
                entity.getStatus().name(), bidsCount, profile,
                entity.getDescription(),
                entity.getAcceptedContentTypes(),
                entity.getRefusedTypes(),
                entity.getAcceptedPaymentMethods().stream().map(Enum::name).toList(),
                entity.getCapacityUnit(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getPricingMode(),
                gridItems,
                entity.getHandoverWindowStart(),
                entity.getHandoverWindowEnd(),
                isFavorite
        );
    }

    private java.math.BigDecimal pricePerKgDisplay(java.math.BigDecimal net, java.util.UUID travelerId) {
        return net == null ? null : priceGridService.displayPrice(net, travelerId);
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
}
