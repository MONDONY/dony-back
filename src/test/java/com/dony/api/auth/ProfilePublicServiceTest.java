package com.dony.api.auth;

import com.dony.api.auth.dto.ProfilePublicResponse;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.ratings.RatingService;
import com.dony.api.ratings.dto.RatingItemResponse;
import com.dony.api.ratings.dto.UserRatingsSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfilePublicService — tests unitaires")
class ProfilePublicServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RatingService ratingService;

    @InjectMocks private ProfilePublicService profilePublicService;

    private static final UUID USER_ID = UUID.randomUUID();

    private UserEntity user;

    @BeforeEach
    void setUp() throws Exception {
        user = new UserEntity();
        setId(user, USER_ID);
        setField(user, "firstName", "Moussa");
        setField(user, "lastName", "Diallo");
        setField(user, "kycStatus", KycStatus.VERIFIED);
        setField(user, "isProAccount", false);
        setField(user, "kiloPro", false);
        setField(user, "totalTrips", 7);
        setField(user, "totalShipments", 5);
        setField(user, "createdAt", LocalDateTime.of(2025, 3, 15, 10, 0));
    }

    private UserRatingsSummaryResponse stubRatingSummary() {
        return new UserRatingsSummaryResponse(
                new BigDecimal("4.80"), 10,
                Map.of(1, 0L, 2, 0L, 3, 0L, 4, 2L, 5, 8L),
                List.of(new RatingItemResponse(5, "Top", LocalDateTime.now(), false)),
                0, 1);
    }

    @Test
    @DisplayName("utilisateur introuvable → 404 NOT_FOUND")
    void getProfilePublic_userNotFound_throws404() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profilePublicService.getProfilePublic(USER_ID))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    DonyBusinessException ex = (DonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
                });
    }

    @Test
    @DisplayName("utilisateur trouvé → retourne profil public complet")
    void getProfilePublic_validUser_returnsFullProfile() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        assertThat(response.userId()).isEqualTo(USER_ID.toString());
        assertThat(response.displayName()).isEqualTo("Moussa D.");
        assertThat(response.avatarUrl()).isNull();
        assertThat(response.kycVerified()).isTrue();
        assertThat(response.completedBidsCount()).isEqualTo(12); // 7 trips + 5 shipments
        assertThat(response.averageRating()).isEqualByComparingTo(new BigDecimal("4.80"));
        assertThat(response.ratingCount()).isEqualTo(10);
        assertThat(response.memberSince()).isEqualTo("Membre depuis mars 2025");
        assertThat(response.badges()).isEmpty();
    }

    @Test
    @DisplayName("kycStatus != VERIFIED → kycVerified false")
    void getProfilePublic_kycNotVerified_kycVerifiedFalse() throws Exception {
        setField(user, "kycStatus", KycStatus.PENDING);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        assertThat(response.kycVerified()).isFalse();
    }

    @Test
    @DisplayName("displayName — prénom seul si pas de nom de famille")
    void getProfilePublic_noLastName_displayNameIsFirstNameOnly() throws Exception {
        setField(user, "lastName", null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        assertThat(response.displayName()).isEqualTo("Moussa");
    }

    @Test
    @DisplayName("displayName — 'Utilisateur' si ni prénom ni nom")
    void getProfilePublic_noName_displayNameIsGeneric() throws Exception {
        setField(user, "firstName", null);
        setField(user, "lastName", null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        assertThat(response.displayName()).isEqualTo("Utilisateur");
    }

    @Test
    @DisplayName("response ne contient jamais phoneNumber")
    void getProfilePublic_neverContainsPhoneNumber() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        // ProfilePublicResponse record fields must not include phone
        assertThat(response).isNotNull();
        // Verify by introspection that no field named "phone" or "phoneNumber" exists in the record
        var fields = java.util.Arrays.stream(ProfilePublicResponse.class.getRecordComponents())
                .map(rc -> rc.getName())
                .toList();
        assertThat(fields).doesNotContain("phone", "phoneNumber");
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static void setId(Object obj, UUID id) throws Exception {
        Field f = obj.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(obj, id);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f;
        try {
            f = obj.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = obj.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(obj, value);
    }
}
