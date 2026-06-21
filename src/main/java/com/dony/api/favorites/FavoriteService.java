package com.dony.api.favorites;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.favorites.dto.FavoriteIdsResponse;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
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

    public FavoriteService(FavoriteRepository favoriteRepository,
                           UserRepository userRepository,
                           AnnouncementRepository announcementRepository,
                           PackageRequestRepository packageRequestRepository) {
        this.favoriteRepository = favoriteRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.packageRequestRepository = packageRequestRepository;
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
