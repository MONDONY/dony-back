package com.dony.api.alerts;

import com.dony.api.alerts.dto.CorridorAlertRequest;
import com.dony.api.alerts.dto.CorridorAlertResponse;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceCreateTest {

    @Mock CorridorAlertRepository alertRepository;
    @Mock UserRepository userRepository;
    @Mock PackageRequestRepository packageRequestRepository;
    @InjectMocks AlertService service;

    final String uid = "firebase-uid";
    final UUID travelerId = UUID.randomUUID();
    UserEntity traveler;

    @BeforeEach
    void setup() {
        traveler = new UserEntity();
        try {
            var f = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(traveler, travelerId);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private CorridorAlertRequest req() {
        return new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, new BigDecimal("2.00"), List.of("Documents"));
    }

    @Test
    void create_persistsAndReturnsResponse() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(traveler));
        when(alertRepository.countByTravelerId(travelerId)).thenReturn(0L);
        when(alertRepository.findAllByTravelerId(travelerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid, req());

        assertThat(resp.departureCity()).isEqualTo("Paris");
        assertThat(resp.arrivalCity()).isEqualTo("Bamako");
        assertThat(resp.active()).isTrue();
        assertThat(resp.matchCount()).isEqualTo(0L);
        verify(alertRepository).save(any(CorridorAlertEntity.class));
    }

    @Test
    void create_unknownTraveler_throwsNotFound() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void create_atCap_throws422() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(traveler));
        when(alertRepository.countByTravelerId(travelerId)).thenReturn(20L);

        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_exactDuplicate_throws409() {
        CorridorAlertEntity existing = new CorridorAlertEntity();
        existing.setTravelerId(travelerId);
        existing.setDepartureCity("Paris");
        existing.setArrivalCity("Bamako");
        existing.setMinWeightKg(new BigDecimal("2.00"));
        existing.setContentCategories(List.of("Documents"));

        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(traveler));
        when(alertRepository.countByTravelerId(travelerId)).thenReturn(1L);
        when(alertRepository.findAllByTravelerId(travelerId)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_nullContentCategories_doesNotNpe() {
        CorridorAlertRequest reqWithNullCategories = new CorridorAlertRequest(
                "Paris", "FR", "Bamako", "ML", null, null, new BigDecimal("2.00"), null);

        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(traveler));
        when(alertRepository.countByTravelerId(travelerId)).thenReturn(0L);
        when(alertRepository.findAllByTravelerId(travelerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid, reqWithNullCategories);

        assertThat(resp.contentCategories()).isNotNull().isEmpty();
        verify(alertRepository).save(any(CorridorAlertEntity.class));
    }
}
