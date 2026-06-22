package com.dony.api.favorites;

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
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.repository.PackageRequestRepository;
import com.dony.api.requests.service.PackageRequestSearchMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final PackageRequestRepository packageRequestRepository;
    private final AnnouncementSearchMapper announcementSearchMapper;
    private final PackageRequestSearchMapper packageRequestSearchMapper;

    public FavoriteService(FavoriteRepository favoriteRepository,
                           UserRepository userRepository,
                           AnnouncementRepository announcementRepository,
                           PackageRequestRepository packageRequestRepository,
                           AnnouncementSearchMapper announcementSearchMapper,
                           PackageRequestSearchMapper packageRequestSearchMapper) {
        this.favoriteRepository = favoriteRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.packageRequestRepository = packageRequestRepository;
        this.announcementSearchMapper = announcementSearchMapper;
        this.packageRequestSearchMapper = packageRequestSearchMapper;
    }

    /**
     * Toggle-add: inserts a new favorite, or revives a soft-deleted one.
     * Idempotent if the row is already active.
     *
     * @throws DonyNotFoundException   if the target does not exist
     * @throws DonyBusinessException   (422) if the caller owns the TRIP target
     */
    public void addFavorite(String firebaseUid, FavoriteTargetType type, UUID targetId) {
        UUID userId = resolveUserId(firebaseUid);
        validateTargetExistsAndNotOwned(userId, type, targetId);

        Optional<FavoriteEntity> existing =
                favoriteRepository.findIncludingDeleted(userId, type.name(), targetId);

        if (existing.isPresent()) {
            FavoriteEntity fav = existing.get();
            if (fav.getDeletedAt() != null) {
                fav.revive();
                favoriteRepository.save(fav);
            }
            // already active -> idempotent, no-op
            return;
        }

        favoriteRepository.save(new FavoriteEntity(userId, type, targetId));
    }

    /**
     * Soft-deletes the active favorite row if present. No-op otherwise.
     */
    public void removeFavorite(String firebaseUid, FavoriteTargetType type, UUID targetId) {
        UUID userId = resolveUserId(firebaseUid);
        favoriteRepository.findByUserIdAndTargetTypeAndTargetId(userId, type, targetId)
                .ifPresent(fav -> {
                    fav.softDelete();
                    favoriteRepository.save(fav);
                });
    }

    /**
     * Returns the sets of favorite target IDs for the caller, split by type.
     */
    @Transactional(readOnly = true)
    public FavoriteIdsResponse getFavoriteIds(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        Set<UUID> trips = new HashSet<>(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP));
        Set<UUID> packageRequests = new HashSet<>(
                favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST));
        return new FavoriteIdsResponse(trips, packageRequests);
    }

    /**
     * Returns the caller's favorite trips as enriched DTOs, with {@code isFavorite=true}.
     * Soft-deleted announcements are automatically excluded (via {@code @Where} on the entity).
     * Announcements with status {@code CANCELLED} are also filtered out.
     * Batch-loads users, bid counts, and grid items in 3 queries total (no N+1).
     */
    @Transactional(readOnly = true)
    public List<AnnouncementSearchResponse> getFavoriteTrips(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        List<UUID> ids = favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP);
        if (ids.isEmpty()) return List.of();
        List<AnnouncementEntity> active = announcementRepository.findAllById(ids).stream()
                .filter(a -> a.getStatus() != AnnouncementStatus.CANCELLED)
                .toList();
        if (active.isEmpty()) return List.of();
        Set<UUID> favIdSet = new HashSet<>(ids); // all are favorites
        return announcementSearchMapper.toSearchResponseList(active, favIdSet);
    }

    /**
     * Returns the caller's favorite package-requests as enriched DTOs, with {@code isFavorite=true}.
     * Soft-deleted or cancelled package-requests are excluded.
     * Batch-loads users, cities, and photos in 3 queries total (no N+1).
     */
    @Transactional(readOnly = true)
    public List<PackageRequestSearchResponse> getFavoritePackageRequests(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        List<UUID> ids = favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST);
        if (ids.isEmpty()) return List.of();
        List<com.dony.api.requests.entity.PackageRequestEntity> active =
                packageRequestRepository.findAllById(ids).stream()
                        .filter(pr -> pr.getStatus() != PackageRequestStatus.CANCELLED)
                        .toList();
        if (active.isEmpty()) return List.of();
        Set<UUID> favIdSet = new HashSet<>(ids); // all are favorites
        return packageRequestSearchMapper.toSearchResponseList(active, favIdSet);
    }

    // --- private helpers ---

    private UUID resolveUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new DonyNotFoundException("Utilisateur introuvable: " + firebaseUid))
                .getId();
    }

    /**
     * Validates that the target exists (404 if missing) and that the caller does not
     * own it (422 if the caller is the traveler of a TRIP target).
     * PACKAGE_REQUEST has no ownership check — a traveler is never the sender/owner.
     */
    private void validateTargetExistsAndNotOwned(UUID userId, FavoriteTargetType type, UUID targetId) {
        switch (type) {
            case TRIP -> {
                AnnouncementEntity trip = announcementRepository.findById(targetId)
                        .orElseThrow(() -> new DonyNotFoundException("Trajet introuvable: " + targetId));
                if (userId.equals(trip.getTravelerId())) {
                    throw new DonyBusinessException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "favorites/own-resource",
                            "Cannot Favorite Own Resource",
                            "Impossible de mettre son propre trajet en favori"
                    );
                }
            }
            case PACKAGE_REQUEST -> {
                if (!packageRequestRepository.existsById(targetId)) {
                    throw new DonyNotFoundException("Demande d'envoi introuvable: " + targetId);
                }
            }
        }
    }
}
