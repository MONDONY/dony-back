package com.dony.api.favorites;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.favorites.dto.FavoriteIdsResponse;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementSearchMapper;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.dto.AnnouncementSearchResponse;
import com.dony.api.requests.dto.PackageRequestSearchResponse;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.repository.PackageRequestRepository;
import com.dony.api.requests.service.PackageRequestSearchMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FavoriteServiceTest {

    @Mock FavoriteRepository favoriteRepository;
    @Mock UserRepository userRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PackageRequestRepository packageRequestRepository;
    @Mock AnnouncementSearchMapper announcementSearchMapper;
    @Mock PackageRequestSearchMapper packageRequestSearchMapper;

    FavoriteService service;

    final String UID = "firebase-uid-123";
    UUID userId;
    UUID tripId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new FavoriteService(favoriteRepository, userRepository,
                announcementRepository, packageRequestRepository,
                announcementSearchMapper, packageRequestSearchMapper);
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

    // --- getFavoriteTrips tests ---

    @Test
    void getFavoriteTrips_emptyIds_returnsEmptyList() {
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP)).thenReturn(List.of());

        var res = service.getFavoriteTrips(UID);

        assertThat(res).isEmpty();
        verify(announcementRepository, never()).findAllById(any());
    }

    @Test
    void getFavoriteTrips_skipsCancelledAndMissing() {
        UUID t1 = UUID.randomUUID(); // active — should be kept
        UUID t2 = UUID.randomUUID(); // cancelled — should be filtered out
        UUID t3 = UUID.randomUUID(); // soft-deleted (absent from findAllById result)
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP))
                .thenReturn(List.of(t1, t2, t3));

        AnnouncementEntity a1 = mock(AnnouncementEntity.class);
        when(a1.getId()).thenReturn(t1);
        when(a1.getStatus()).thenReturn(AnnouncementStatus.ACTIVE);

        AnnouncementEntity a2 = mock(AnnouncementEntity.class);
        when(a2.getId()).thenReturn(t2);
        when(a2.getStatus()).thenReturn(AnnouncementStatus.CANCELLED);

        // t3 is absent (soft-deleted — @Where excludes it)
        when(announcementRepository.findAllById(anyCollection())).thenReturn(List.of(a1, a2));

        AnnouncementSearchResponse dto = mock(AnnouncementSearchResponse.class);
        when(announcementSearchMapper.toSearchResponse(a1, true)).thenReturn(dto);

        var res = service.getFavoriteTrips(UID);

        assertThat(res).hasSize(1);
        assertThat(res.get(0)).isSameAs(dto);
        verify(announcementSearchMapper).toSearchResponse(a1, true);
        verify(announcementSearchMapper, never()).toSearchResponse(a2, true);
    }

    @Test
    void getFavoriteTrips_isFavoriteTruePassedToMapper() {
        UUID t1 = UUID.randomUUID();
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP))
                .thenReturn(List.of(t1));

        AnnouncementEntity a1 = mock(AnnouncementEntity.class);
        when(a1.getStatus()).thenReturn(AnnouncementStatus.FULL);
        when(announcementRepository.findAllById(anyCollection())).thenReturn(List.of(a1));
        AnnouncementSearchResponse dto = mock(AnnouncementSearchResponse.class);
        when(announcementSearchMapper.toSearchResponse(a1, true)).thenReturn(dto);

        service.getFavoriteTrips(UID);

        verify(announcementSearchMapper).toSearchResponse(a1, true);
    }

    // --- getFavoritePackageRequests tests ---

    @Test
    void getFavoritePackageRequests_emptyIds_returnsEmptyList() {
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of());

        var res = service.getFavoritePackageRequests(UID);

        assertThat(res).isEmpty();
        verify(packageRequestRepository, never()).findAllById(any());
    }

    @Test
    void getFavoritePackageRequests_skipsCancelledAndMissing() {
        UUID p1 = UUID.randomUUID(); // OPEN — should be kept
        UUID p2 = UUID.randomUUID(); // CANCELLED — should be filtered out
        UUID p3 = UUID.randomUUID(); // soft-deleted (absent from findAllById result)
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of(p1, p2, p3));

        PackageRequestEntity pr1 = mock(PackageRequestEntity.class);
        when(pr1.getId()).thenReturn(p1);
        when(pr1.getStatus()).thenReturn(PackageRequestStatus.OPEN);

        PackageRequestEntity pr2 = mock(PackageRequestEntity.class);
        when(pr2.getId()).thenReturn(p2);
        when(pr2.getStatus()).thenReturn(PackageRequestStatus.CANCELLED);

        // p3 absent (soft-deleted)
        when(packageRequestRepository.findAllById(anyCollection())).thenReturn(List.of(pr1, pr2));

        PackageRequestSearchResponse dto = mock(PackageRequestSearchResponse.class);
        when(packageRequestSearchMapper.toSearchResponse(pr1, true)).thenReturn(dto);

        var res = service.getFavoritePackageRequests(UID);

        assertThat(res).hasSize(1);
        assertThat(res.get(0)).isSameAs(dto);
        verify(packageRequestSearchMapper).toSearchResponse(pr1, true);
        verify(packageRequestSearchMapper, never()).toSearchResponse(pr2, true);
    }

    @Test
    void getFavoritePackageRequests_isFavoriteTruePassedToMapper() {
        UUID p1 = UUID.randomUUID();
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of(p1));

        PackageRequestEntity pr1 = mock(PackageRequestEntity.class);
        when(pr1.getStatus()).thenReturn(PackageRequestStatus.NEGOTIATING);
        when(packageRequestRepository.findAllById(anyCollection())).thenReturn(List.of(pr1));
        PackageRequestSearchResponse dto = mock(PackageRequestSearchResponse.class);
        when(packageRequestSearchMapper.toSearchResponse(pr1, true)).thenReturn(dto);

        service.getFavoritePackageRequests(UID);

        verify(packageRequestSearchMapper).toSearchResponse(pr1, true);
    }
}
