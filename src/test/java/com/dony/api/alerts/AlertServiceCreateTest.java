package com.dony.api.alerts;

import com.dony.api.alerts.dto.CorridorAlertRequest;
import com.dony.api.alerts.dto.CorridorAlertResponse;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceCreateTest {

    @Mock CorridorAlertRepository alertRepository;
    @Mock UserRepository userRepository;
    @Mock PackageRequestRepository packageRequestRepository;
    @Mock AnnouncementRepository announcementRepository;
    @InjectMocks AlertService service;

    final String uid = "firebase-uid";
    final UUID ownerId = UUID.randomUUID();
    UserEntity owner;

    @BeforeEach
    void setup() {
        owner = new UserEntity();
        try {
            var f = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(owner, ownerId);
        } catch (Exception e) { throw new RuntimeException(e); }
        owner.setRoles(Set.of(Role.TRAVELER));
    }

    private CorridorAlertRequest req() {
        return new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, new BigDecimal("2.00"), List.of("Documents"),
                AlertDirection.TRAVELER_WANTS_PACKAGES, null);
    }

    private CorridorAlertRequest senderReq() {
        return new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, null, null,
                AlertDirection.SENDER_WANTS_TRIPS, null);
    }

    @Test
    void create_persistsAndReturnsResponse() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid, req());

        assertThat(resp.departureCity()).isEqualTo("Paris");
        assertThat(resp.arrivalCity()).isEqualTo("Bamako");
        assertThat(resp.active()).isTrue();
        assertThat(resp.matchCount()).isEqualTo(0L);

        ArgumentCaptor<CorridorAlertEntity> captor = ArgumentCaptor.forClass(CorridorAlertEntity.class);
        verify(alertRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(AlertDirection.TRAVELER_WANTS_PACKAGES);
    }

    @Test
    void create_unknownUser_throwsNotFound() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void create_atCap_throws422() {
        // Item 4: cap check now uses findAllByOwnerId — return 20 items to trigger the limit
        List<CorridorAlertEntity> fullList = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            CorridorAlertEntity e = new CorridorAlertEntity();
            e.setOwnerId(ownerId);
            e.setDepartureCity("City" + i);
            e.setArrivalCity("Dest" + i);
            e.setContentCategories(List.of());
            fullList.add(e);
        }
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(fullList);

        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_exactDuplicate_throws409() {
        CorridorAlertEntity existing = new CorridorAlertEntity();
        existing.setOwnerId(ownerId);
        existing.setDepartureCity("Paris");
        existing.setArrivalCity("Bamako");
        existing.setMinWeightKg(new BigDecimal("2.00"));
        existing.setContentCategories(List.of("Documents"));
        existing.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);

        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_nullContentCategories_doesNotNpe() {
        CorridorAlertRequest reqWithNullCategories = new CorridorAlertRequest(
                "Paris", "FR", "Bamako", "ML", null, null, new BigDecimal("2.00"), null,
                AlertDirection.TRAVELER_WANTS_PACKAGES, null);

        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid, reqWithNullCategories);

        assertThat(resp.contentCategories()).isNotNull().isEmpty();
        verify(alertRepository).save(any(CorridorAlertEntity.class));
    }

    // --- New tests for Task 4 ---

    @Test
    void create_senderWantsTrips_withTravelerRole_throws403() {
        // TRAVELER role + SENDER_WANTS_TRIPS direction → 403
        owner.setRoles(Set.of(Role.TRAVELER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertRequest req = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, null, null,
                AlertDirection.SENDER_WANTS_TRIPS, null);

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((DonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("alert-direction-not-allowed");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_travelerWantsPackages_withSenderRole_throws403() {
        // SENDER role + TRAVELER_WANTS_PACKAGES direction → 403
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((DonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("alert-direction-not-allowed");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_tripDirection_withWeightFilter_throws422() {
        // SENDER_WANTS_TRIPS + minWeightKg set → 422
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertRequest req = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, new BigDecimal("5.00"), null,
                AlertDirection.SENDER_WANTS_TRIPS, null);

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((DonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("alert-trip-filters-unsupported");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_tripDirection_withCategoryFilter_throws422() {
        // SENDER_WANTS_TRIPS + contentCategories non-empty → 422
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertRequest req = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, null, List.of("Documents"),
                AlertDirection.SENDER_WANTS_TRIPS, null);

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((DonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("alert-trip-filters-unsupported");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_duplicateSameCorridorDifferentDirection_isAllowed() {
        // Same corridor but different direction → NOT a duplicate
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertEntity existing = new CorridorAlertEntity();
        existing.setOwnerId(ownerId);
        existing.setDepartureCity("Paris");
        existing.setArrivalCity("Bamako");
        existing.setMinWeightKg(null);
        existing.setContentCategories(List.of());
        existing.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);

        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(existing));
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // senderReq() uses SENDER_WANTS_TRIPS, no weight/categories
        CorridorAlertResponse resp = service.create(uid, senderReq());

        assertThat(resp.direction()).isEqualTo(AlertDirection.SENDER_WANTS_TRIPS);
        verify(alertRepository).save(any(CorridorAlertEntity.class));
    }

    @Test
    void create_senderWantsTrips_ok() {
        // SENDER role + SENDER_WANTS_TRIPS direction + no filters → success
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid, senderReq());

        assertThat(resp.direction()).isEqualTo(AlertDirection.SENDER_WANTS_TRIPS);
        assertThat(resp.active()).isTrue();

        ArgumentCaptor<CorridorAlertEntity> captor = ArgumentCaptor.forClass(CorridorAlertEntity.class);
        verify(alertRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(AlertDirection.SENDER_WANTS_TRIPS);
    }

    // --- Zone de remise (option SENDER_WANTS_TRIPS) ---

    private CorridorAlertRequest senderZoneReq(BigDecimal lat, BigDecimal lng, Integer radiusKm) {
        return new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, null, null,
                AlertDirection.SENDER_WANTS_TRIPS, null,
                lat, lng, radiusKm, "Châtelet, Paris");
    }

    @Test
    void create_zone_withTravelerDirection_throws422NotAllowed() {
        owner.setRoles(Set.of(Role.TRAVELER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertRequest req = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, null, null,
                AlertDirection.TRAVELER_WANTS_PACKAGES, null,
                new BigDecimal("48.85"), new BigDecimal("2.35"), 20, "Paris");

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((DonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("alert-zone-not-allowed");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_zone_incomplete_throws422() {
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        // centre posé mais rayon manquant
        CorridorAlertRequest req = senderZoneReq(new BigDecimal("48.85"), new BigDecimal("2.35"), null);

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((DonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("alert-zone-incomplete");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_zone_radiusTooLarge_throws422() {
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertRequest req = senderZoneReq(new BigDecimal("48.85"), new BigDecimal("2.35"), 400);

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((DonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("alert-zone-radius-invalid");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_zone_valid_persistsZone() {
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid,
                senderZoneReq(new BigDecimal("48.856600"), new BigDecimal("2.352200"), 20));

        assertThat(resp.radiusKm()).isEqualTo(20);
        assertThat(resp.centerLabel()).isEqualTo("Châtelet, Paris");
        assertThat(resp.centerLat()).isEqualByComparingTo(new BigDecimal("48.856600"));

        ArgumentCaptor<CorridorAlertEntity> captor = ArgumentCaptor.forClass(CorridorAlertEntity.class);
        verify(alertRepository).save(captor.capture());
        assertThat(captor.getValue().hasPickupZone()).isTrue();
        assertThat(captor.getValue().getRadiusKm()).isEqualTo(20);
    }
}
