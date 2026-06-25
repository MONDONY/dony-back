package com.dony.api.admin;

import com.dony.api.admin.dto.AdminRatingResponse;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.ratings.RatingEntity;
import com.dony.api.ratings.RatingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminRatingsController {

    private final RatingRepository ratingRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;

    public AdminRatingsController(RatingRepository ratingRepo,
                                  UserRepository userRepo,
                                  AuditService auditService) {
        this.ratingRepo = ratingRepo;
        this.userRepo = userRepo;
        this.auditService = auditService;
    }

    @GetMapping("/admin/ratings")
    public ResponseEntity<Page<AdminRatingResponse>> listRatings(
            @RequestParam(required = false) Boolean flagged,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Integer maxScore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<RatingEntity> entities = ratingRepo.findAdminFiltered(
                flagged, minScore, maxScore,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        // Batch load all referenced users
        Set<UUID> userIds = new HashSet<>();
        for (RatingEntity r : entities.getContent()) {
            if (r.getRaterId() != null) userIds.add(r.getRaterId());
            if (r.getRatedUserId() != null) userIds.add(r.getRatedUserId());
        }
        List<UserEntity> users = userRepo.findAllById(userIds);
        Map<UUID, UserEntity> usersById = users.stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));

        Page<AdminRatingResponse> result = entities.map(r -> AdminRatingResponse.from(r, usersById));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/admin/ratings/{id}/exclude")
    @Transactional
    public ResponseEntity<AdminRatingResponse> excludeRating(@PathVariable UUID id) {
        RatingEntity rating = findRatingOrThrow(id);
        rating.setFlagged(true);
        ratingRepo.save(rating);
        auditService.log("RATING", id, "RATING_EXCLUDED", null, Map.of("ratingId", id.toString()));

        Map<UUID, UserEntity> usersById = buildUsersMap(rating);
        return ResponseEntity.ok(AdminRatingResponse.from(rating, usersById));
    }

    @DeleteMapping("/admin/ratings/{id}")
    @Transactional
    public ResponseEntity<Void> deleteRating(@PathVariable UUID id) {
        RatingEntity rating = findRatingOrThrow(id);
        rating.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        ratingRepo.save(rating);
        auditService.log("RATING", id, "RATING_DELETED", null, Map.of("ratingId", id.toString()));
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RatingEntity findRatingOrThrow(UUID id) {
        return ratingRepo.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "rating-not-found", "Not Found", "Avis introuvable"));
    }

    private Map<UUID, UserEntity> buildUsersMap(RatingEntity r) {
        Set<UUID> userIds = new HashSet<>();
        if (r.getRaterId() != null) userIds.add(r.getRaterId());
        if (r.getRatedUserId() != null) userIds.add(r.getRatedUserId());
        List<UserEntity> users = userRepo.findAllById(userIds);
        return users.stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
    }
}
