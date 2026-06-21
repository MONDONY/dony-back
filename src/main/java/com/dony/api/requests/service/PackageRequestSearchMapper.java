package com.dony.api.requests.service;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.StorageService;
import com.dony.api.requests.dto.PackageRequestPhotoResponse;
import com.dony.api.requests.dto.PackageRequestSearchResponse;
import com.dony.api.requests.entity.PackageRequestEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stateless mapper: converts a {@link PackageRequestEntity} to a {@link PackageRequestSearchResponse}.
 * Extracted from {@link PackageRequestService#toSearchResponse} so that packages outside
 * {@code requests/} (e.g. {@code favorites/}) can map package-request entities without
 * importing the full {@code PackageRequestService} and creating a cross-package service cycle.
 *
 * <p>No business logic lives here — only field projection and helper calls.
 */
@Component
public class PackageRequestSearchMapper {

    private final UserRepository userRepository;
    private final com.dony.api.city.CityRepository cityRepository;
    private final StorageService storageService;
    private final PackageRequestPhotoService photoService;

    public PackageRequestSearchMapper(UserRepository userRepository,
                                      com.dony.api.city.CityRepository cityRepository,
                                      StorageService storageService,
                                      PackageRequestPhotoService photoService) {
        this.userRepository = userRepository;
        this.cityRepository = cityRepository;
        this.storageService = storageService;
        this.photoService = photoService;
    }

    /**
     * Maps an entity to the search DTO.
     *
     * @param entity     the package-request entity (must not be null)
     * @param isFavorite whether the viewing user has favorited this request
     * @return the populated search response record
     */
    public PackageRequestSearchResponse toSearchResponse(PackageRequestEntity entity, boolean isFavorite) {
        UserEntity sender = userRepository.findById(entity.getSenderId()).orElse(null);
        String displayName = buildSenderDisplayName(sender);
        double averageRating = sender != null && sender.getAverageRating() != null
                ? sender.getAverageRating().doubleValue() : 0.0;
        int totalRatings = sender != null ? sender.getRatingCount() : 0;
        boolean kycVerified = sender != null && sender.getKycStatus() == KycStatus.VERIFIED;
        var senderProfile = new PackageRequestSearchResponse.SenderPublicProfile(
                entity.getSenderId(), displayName, averageRating, totalRatings, kycVerified,
                storageService.avatarUrl(sender != null ? sender.getAvatarUrl() : null)
        );
        var depCity = cityRepository.findFirstByNameIgnoreCase(entity.getDepartureCity()).orElse(null);
        var arrCity = cityRepository.findFirstByNameIgnoreCase(entity.getArrivalCity()).orElse(null);
        List<PackageRequestPhotoResponse> photos = photoService.activePhotos(entity.getId());
        String photoUrl = photos.isEmpty() ? entity.getPhotoUrl() : photos.get(0).url();
        return new PackageRequestSearchResponse(
                entity.getId(), entity.getDepartureCity(), entity.getArrivalCity(),
                depCity != null ? depCity.getLatitude() : null,
                depCity != null ? depCity.getLongitude() : null,
                arrCity != null ? arrCity.getLatitude() : null,
                arrCity != null ? arrCity.getLongitude() : null,
                entity.getDesiredDate(),
                entity.getDateToleranceDays() != null ? entity.getDateToleranceDays().intValue() : 0,
                entity.getWeightKg(), entity.getParcelSize(), entity.getTransportMode(),
                entity.getContentCategory(),
                entity.getTargetPriceEur(), entity.isNegotiable(), photoUrl,
                entity.getPickupNeighborhood(), entity.getDeliveryNeighborhood(),
                senderProfile,
                entity.getAcceptedPaymentMethods(),
                photos,
                isFavorite
        );
    }

    private String buildSenderDisplayName(UserEntity user) {
        if (user == null) return "Expéditeur";
        String first = user.getFirstName();
        String last = user.getLastName();
        if (first != null && !first.isBlank()) {
            if (last != null && !last.isBlank()) {
                return first + " " + last.charAt(0) + ".";
            }
            return first;
        }
        return "Expéditeur";
    }
}
