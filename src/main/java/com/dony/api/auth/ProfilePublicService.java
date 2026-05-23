package com.dony.api.auth;

import com.dony.api.auth.dto.ProfilePublicResponse;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.ratings.RatingService;
import com.dony.api.ratings.dto.UserRatingsSummaryResponse;
import com.dony.api.settings.UserBusinessPrefsEntity;
import com.dony.api.settings.UserBusinessPrefsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ProfilePublicService {

    private final UserRepository userRepository;
    private final RatingService ratingService;
    private final UserBusinessPrefsRepository userBusinessPrefsRepository;

    public ProfilePublicService(UserRepository userRepository,
                                RatingService ratingService,
                                UserBusinessPrefsRepository userBusinessPrefsRepository) {
        this.userRepository = userRepository;
        this.ratingService = ratingService;
        this.userBusinessPrefsRepository = userBusinessPrefsRepository;
    }

    public ProfilePublicResponse getProfilePublic(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        String displayName = buildDisplayName(user);
        boolean kycVerified = user.getKycStatus() == KycStatus.VERIFIED;
        int completedBidsCount = user.getTotalTrips() + user.getTotalShipments();
        String memberSince = buildMemberSince(user);

        UserRatingsSummaryResponse ratingSummary = ratingService.getUserRatings(userId, 0, 3);

        UserBusinessPrefsEntity prefs = userBusinessPrefsRepository.findById(userId).orElse(null);
        String contactMode = prefs != null ? prefs.getContactMode() : null;
        Integer responseDelayHours = prefs != null ? prefs.getResponseDelayHours() : null;

        return new ProfilePublicResponse(
                userId.toString(),
                displayName,
                null,
                kycVerified,
                user.isProAccount(),
                user.isKiloPro(),
                completedBidsCount,
                ratingSummary.averageRating(),
                ratingSummary.ratingCount(),
                memberSince,
                List.of(),
                contactMode,
                responseDelayHours
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
        return "Utilisateur";
    }

    private String buildMemberSince(UserEntity user) {
        if (user.getCreatedAt() == null) {
            return "Membre depuis récemment";
        }
        String month = user.getCreatedAt()
                .getMonth()
                .getDisplayName(TextStyle.FULL, Locale.FRENCH);
        int year = user.getCreatedAt().getYear();
        return "Membre depuis " + month + " " + year;
    }
}
