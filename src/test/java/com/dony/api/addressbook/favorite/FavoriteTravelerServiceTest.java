package com.dony.api.addressbook.favorite;

import com.dony.api.addressbook.favorite.dto.AddFavoriteTravelerRequest;
import com.dony.api.addressbook.favorite.dto.FavoriteTravelerDto;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FavoriteTravelerServiceTest {

    @Mock
    private FavoriteTravelerRepository repository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    private FavoriteTravelerService service;

    private UUID userId;
    private UUID travelerId;

    @BeforeEach
    void setUp() {
        service = new FavoriteTravelerService(repository, userRepository, auditService);
        userId = UUID.randomUUID();
        travelerId = UUID.randomUUID();
    }

    private FavoriteTravelerEntity buildEntity(UUID userId, UUID travelerId) {
        FavoriteTravelerEntity e = new FavoriteTravelerEntity();
        e.setUserId(userId);
        e.setTravelerId(travelerId);
        return e;
    }

    private UserEntity buildTraveler(UUID id) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + id);
        u.setFirstName("Ousmane");
        u.setLastName("Diallo");
        return u;
    }

    @Test
    void findAll_returnsFavoritesForUser() {
        FavoriteTravelerEntity e = buildEntity(userId, travelerId);
        UserEntity traveler = buildTraveler(travelerId);

        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(e));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        List<FavoriteTravelerDto> result = service.findAll(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).displayName()).isEqualTo("Ousmane D.");
    }

    @Test
    void add_withValidTraveler_succeeds() {
        UserEntity traveler = buildTraveler(travelerId);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(repository.existsByUserIdAndTravelerId(userId, travelerId)).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AddFavoriteTravelerRequest request = new AddFavoriteTravelerRequest(travelerId, "Très fiable");
        FavoriteTravelerDto result = service.add(userId, request);

        assertThat(result.travelerId()).isEqualTo(travelerId);
        assertThat(result.notes()).isEqualTo("Très fiable");
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void add_travelerDoesNotExist_throwsNotFoundException() {
        when(userRepository.findById(travelerId)).thenReturn(Optional.empty());

        AddFavoriteTravelerRequest request = new AddFavoriteTravelerRequest(travelerId, null);

        assertThatThrownBy(() -> service.add(userId, request))
                .isInstanceOf(DonyNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void add_selfFavorite_throwsBusinessException() {
        UserEntity self = buildTraveler(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(self));

        AddFavoriteTravelerRequest request = new AddFavoriteTravelerRequest(userId, null);

        assertThatThrownBy(() -> service.add(userId, request))
                .isInstanceOf(DonyBusinessException.class)
                .hasMessageContaining("favoris");
    }

    @Test
    void add_alreadyFavorite_throwsConflictException() {
        UserEntity traveler = buildTraveler(travelerId);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(repository.existsByUserIdAndTravelerId(userId, travelerId)).thenReturn(true);

        AddFavoriteTravelerRequest request = new AddFavoriteTravelerRequest(travelerId, null);

        assertThatThrownBy(() -> service.add(userId, request))
                .isInstanceOf(DonyBusinessException.class)
                .hasMessageContaining("favoris");
    }

    @Test
    void remove_softDeletesAndLogsAudit() {
        FavoriteTravelerEntity entity = buildEntity(userId, travelerId);

        when(repository.findByUserIdAndTravelerId(userId, travelerId)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.remove(userId, travelerId);

        assertThat(entity.getDeletedAt()).isNotNull();
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void remove_notFound_throwsNotFoundException() {
        when(repository.findByUserIdAndTravelerId(userId, travelerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(userId, travelerId))
                .isInstanceOf(DonyNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void findAll_whenTravelerNotFound_returnsVoyageurDisplayName() {
        FavoriteTravelerEntity e = buildEntity(userId, travelerId);
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(e));
        when(userRepository.findById(travelerId)).thenReturn(Optional.empty());

        List<FavoriteTravelerDto> result = service.findAll(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).displayName()).isEqualTo("Voyageur");
        assertThat(result.get(0).averageRating()).isNull();
    }

    @Test
    void add_travelerWithNoFirstName_displayNameIsVoyageur() {
        UserEntity traveler = new UserEntity();
        traveler.setFirstName(null);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(repository.existsByUserIdAndTravelerId(userId, travelerId)).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FavoriteTravelerDto result = service.add(userId, new AddFavoriteTravelerRequest(travelerId, null));

        assertThat(result.displayName()).isEqualTo("Voyageur");
    }

    @Test
    void add_travelerWithFirstNameOnly_displayNameIsFirstName() {
        UserEntity traveler = new UserEntity();
        traveler.setFirstName("Ahmed");
        traveler.setLastName(null);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(repository.existsByUserIdAndTravelerId(userId, travelerId)).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FavoriteTravelerDto result = service.add(userId, new AddFavoriteTravelerRequest(travelerId, null));

        assertThat(result.displayName()).isEqualTo("Ahmed");
    }
}
