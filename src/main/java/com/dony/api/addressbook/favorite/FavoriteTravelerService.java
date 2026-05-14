package com.dony.api.addressbook.favorite;

import com.dony.api.addressbook.favorite.dto.AddFavoriteTravelerRequest;
import com.dony.api.addressbook.favorite.dto.FavoriteTravelerDto;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FavoriteTravelerService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteTravelerService.class);

    private final FavoriteTravelerRepository repository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public FavoriteTravelerService(FavoriteTravelerRepository repository,
                                   UserRepository userRepository,
                                   AuditService auditService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    public List<FavoriteTravelerDto> findAll(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(fav -> {
                    UserEntity traveler = userRepository.findById(fav.getTravelerId()).orElse(null);
                    return toDto(fav, traveler);
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public FavoriteTravelerDto add(UUID userId, AddFavoriteTravelerRequest request) {
        UserEntity traveler = userRepository.findById(request.travelerId())
                .orElseThrow(() -> new DonyNotFoundException("User", request.travelerId()));

        if (userId.equals(request.travelerId())) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "self-favorite", "Unprocessable", "Vous ne pouvez pas vous ajouter à vos favoris");
        }

        if (repository.existsByUserIdAndTravelerId(userId, request.travelerId())) {
            throw new DonyBusinessException(HttpStatus.CONFLICT,
                    "already-favorite", "Conflict", "Ce voyageur est déjà dans vos favoris");
        }

        FavoriteTravelerEntity entity = new FavoriteTravelerEntity();
        entity.setUserId(userId);
        entity.setTravelerId(request.travelerId());
        entity.setNotes(request.notes());

        repository.save(entity);

        auditService.log("FAVORITE_TRAVELER", entity.getId(), "FAVORITE_TRAVELER_ADDED", userId,
                Map.of("travelerId", request.travelerId().toString()));

        log.info("FavoriteTraveler added: id={} userId={} travelerId={}", entity.getId(), userId, request.travelerId());
        return toDto(entity, traveler);
    }

    @Transactional
    public void remove(UUID userId, UUID travelerId) {
        FavoriteTravelerEntity entity = repository.findByUserIdAndTravelerId(userId, travelerId)
                .orElseThrow(() -> new DonyNotFoundException("FavoriteTraveler", travelerId));

        entity.softDelete();
        repository.save(entity);

        auditService.log("FAVORITE_TRAVELER", entity.getId(), "FAVORITE_TRAVELER_REMOVED", userId,
                Map.of("travelerId", travelerId.toString()));

        log.info("FavoriteTraveler removed: userId={} travelerId={}", userId, travelerId);
    }

    private FavoriteTravelerDto toDto(FavoriteTravelerEntity entity, UserEntity traveler) {
        String displayName = buildDisplayName(traveler);
        return new FavoriteTravelerDto(
                entity.getId(),
                entity.getTravelerId(),
                displayName,
                traveler != null ? traveler.getAverageRating() : null,
                entity.getNotes(),
                entity.getCreatedAt()
        );
    }

    private String buildDisplayName(UserEntity user) {
        if (user == null) return "Voyageur";
        String first = user.getFirstName();
        String last = user.getLastName();
        if (first != null && !first.isBlank()) {
            if (last != null && !last.isBlank()) {
                return first + " " + last.charAt(0) + ".";
            }
            return first;
        }
        return "Voyageur";
    }
}
