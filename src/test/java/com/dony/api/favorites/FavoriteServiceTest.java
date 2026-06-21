package com.dony.api.favorites;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.favorites.dto.FavoriteIdsResponse;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FavoriteServiceTest {

    @Mock FavoriteRepository favoriteRepository;
    @Mock UserRepository userRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PackageRequestRepository packageRequestRepository;

    FavoriteService service;

    final String UID = "firebase-uid-123";
    UUID userId;
    UUID tripId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new FavoriteService(favoriteRepository, userRepository,
                announcementRepository, packageRequestRepository);
        userId = UUID.randomUUID();
        tripId = UUID.randomUUID();

        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(userId);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
    }

    private AnnouncementEntity tripOwnedBy(UUID ownerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(ownerId);
        return a;
    }

    // --- addFavorite tests ---

    @Test
    void addTrip_insertsWhenAbsent() {
        when(announcementRepository.findById(tripId))
                .thenReturn(Optional.of(tripOwnedBy(UUID.randomUUID())));
        when(favoriteRepository.findIncludingDeleted(eq(userId), eq("TRIP"), eq(tripId)))
                .thenReturn(Optional.empty());

        service.addFavorite(UID, FavoriteTargetType.TRIP, tripId);

        verify(favoriteRepository).save(any(FavoriteEntity.class));
    }

    @Test
    void addTrip_idempotentWhenActiveExists() {
        when(announcementRepository.findById(tripId))
                .thenReturn(Optional.of(tripOwnedBy(UUID.randomUUID())));
        FavoriteEntity active = new FavoriteEntity(userId, FavoriteTargetType.TRIP, tripId);
        // active row has deletedAt == null
        when(favoriteRepository.findIncludingDeleted(eq(userId), eq("TRIP"), eq(tripId)))
                .thenReturn(Optional.of(active));

        service.addFavorite(UID, FavoriteTargetType.TRIP, tripId);

        // already active -> no new save (save may only be called for the same existing object
        // with no modification, but NOT for a brand-new entity)
        verify(favoriteRepository, never()).save(argThat(f -> f != active));
    }

    @Test
    void addTrip_revivesSoftDeleted() {
        when(announcementRepository.findById(tripId))
                .thenReturn(Optional.of(tripOwnedBy(UUID.randomUUID())));
        FavoriteEntity deleted = new FavoriteEntity(userId, FavoriteTargetType.TRIP, tripId);
        deleted.softDelete(); // marks deletedAt != null
        when(favoriteRepository.findIncludingDeleted(eq(userId), eq("TRIP"), eq(tripId)))
                .thenReturn(Optional.of(deleted));

        service.addFavorite(UID, FavoriteTargetType.TRIP, tripId);

        // should revive and save
        verify(favoriteRepository).save(deleted);
        assertThat(deleted.getDeletedAt()).isNull();
    }

    @Test
    void addTrip_rejectsOwnTrip() {
        when(announcementRepository.findById(tripId))
                .thenReturn(Optional.of(tripOwnedBy(userId))); // owner == caller

        assertThatThrownBy(() -> service.addFavorite(UID, FavoriteTargetType.TRIP, tripId))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(ex -> {
                    DonyBusinessException dbe = (DonyBusinessException) ex;
                    assertThat(dbe.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void addTrip_notFoundWhenMissing() {
        when(announcementRepository.findById(tripId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addFavorite(UID, FavoriteTargetType.TRIP, tripId))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void addPackageRequest_insertsWhenAbsent() {
        UUID reqId = UUID.randomUUID();
        when(packageRequestRepository.existsById(reqId)).thenReturn(true);
        when(favoriteRepository.findIncludingDeleted(eq(userId), eq("PACKAGE_REQUEST"), eq(reqId)))
                .thenReturn(Optional.empty());

        service.addFavorite(UID, FavoriteTargetType.PACKAGE_REQUEST, reqId);

        verify(favoriteRepository).save(any(FavoriteEntity.class));
    }

    @Test
    void addPackageRequest_notFoundWhenMissing() {
        UUID reqId = UUID.randomUUID();
        when(packageRequestRepository.existsById(reqId)).thenReturn(false);

        assertThatThrownBy(() -> service.addFavorite(UID, FavoriteTargetType.PACKAGE_REQUEST, reqId))
                .isInstanceOf(DonyNotFoundException.class);
    }

    // --- removeFavorite tests ---

    @Test
    void removeTrip_softDeletesActive() {
        FavoriteEntity active = new FavoriteEntity(userId, FavoriteTargetType.TRIP, tripId);
        when(favoriteRepository.findByUserIdAndTargetTypeAndTargetId(userId, FavoriteTargetType.TRIP, tripId))
                .thenReturn(Optional.of(active));

        service.removeFavorite(UID, FavoriteTargetType.TRIP, tripId);

        verify(favoriteRepository).save(active);
        assertThat(active.getDeletedAt()).isNotNull();
    }

    @Test
    void removeTrip_noOpWhenAbsent() {
        when(favoriteRepository.findByUserIdAndTargetTypeAndTargetId(userId, FavoriteTargetType.TRIP, tripId))
                .thenReturn(Optional.empty());

        service.removeFavorite(UID, FavoriteTargetType.TRIP, tripId);

        verify(favoriteRepository, never()).save(any());
    }

    // --- getFavoriteIds tests ---

    @Test
    void getFavoriteIds_returnsBothSets() {
        UUID reqId = UUID.randomUUID();
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP)).thenReturn(List.of(tripId));
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST)).thenReturn(List.of(reqId));

        FavoriteIdsResponse res = service.getFavoriteIds(UID);

        assertThat(res.trips()).containsExactly(tripId);
        assertThat(res.packageRequests()).containsExactly(reqId);
    }

    @Test
    void getFavoriteIds_returnsEmptySetsWhenNoFavorites() {
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP)).thenReturn(List.of());
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST)).thenReturn(List.of());

        FavoriteIdsResponse res = service.getFavoriteIds(UID);

        assertThat(res.trips()).isEmpty();
        assertThat(res.packageRequests()).isEmpty();
    }
}
