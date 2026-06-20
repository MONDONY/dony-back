package com.dony.api.alerts;

import com.dony.api.alerts.dto.AlertTripMatchDto;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.common.MatchingTextUtil;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.TransportMode;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceTripMatchesTest {

    @Mock CorridorAlertRepository alertRepository;
    @Mock UserRepository userRepository;
    @Mock PackageRequestRepository packageRequestRepository;
    @Mock AnnouncementRepository announcementRepository;
    @InjectMocks AlertService service;

    final String uid = "firebase-uid";
    final UUID ownerId = UUID.randomUUID();
    final UUID alertId = UUID.randomUUID();
    UserEntity owner;

    @BeforeEach
    void setup() {
        owner = new UserEntity();
        setId(owner, ownerId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
    }

    private static void setId(Object target, UUID id) {
        try {
            var f = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private CorridorAlertEntity senderAlert() {
        CorridorAlertEntity a = new CorridorAlertEntity();
        setId(a, alertId);
        a.setOwnerId(ownerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDateFrom(LocalDate.of(2026, 7, 1));
        a.setDateTo(LocalDate.of(2026, 7, 31));
        a.setContentCategories(List.of());
        a.setActive(true);
        a.setDirection(AlertDirection.SENDER_WANTS_TRIPS);
        return a;
    }

    private AnnouncementEntity trip(UUID travelerId, LocalDate date, BigDecimal availableKg, BigDecimal pricePerKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, UUID.randomUUID());
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(date);
        a.setAvailableKg(availableKg);
        a.setPricePerKg(pricePerKg);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Addr");
        a.setPickupLat(BigDecimal.ONE);
        a.setPickupLng(BigDecimal.ONE);
        a.setDeliveryAddressLabel("Dest");
        a.setDeliveryLat(BigDecimal.ONE);
        a.setDeliveryLng(BigDecimal.ONE);
        a.setTotalKg(availableKg);
        return a;
    }

    private UserEntity traveler(UUID id, String firstName, String lastName, BigDecimal rating) {
        UserEntity u = new UserEntity();
        setId(u, id);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setAverageRating(rating);
        return u;
    }

    @Test
    void getTripMatches_senderAlert_returnsOnlyInWindowTrip() {
        UUID travelerId = UUID.randomUUID();
        AnnouncementEntity inWindow = trip(travelerId, LocalDate.of(2026, 7, 10),
                new BigDecimal("15.00"), new BigDecimal("8.50"));
        AnnouncementEntity outOfWindow = trip(UUID.randomUUID(), LocalDate.of(2026, 8, 15),
                new BigDecimal("10.00"), new BigDecimal("7.00"));

        UserEntity travelerEntity = traveler(travelerId, "Moussa", "Diallo", new BigDecimal("4.7"));

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(senderAlert()));
        when(announcementRepository.findActiveByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(inWindow, outOfWindow));
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(travelerEntity));

        List<AlertTripMatchDto> matches = service.getTripMatches(uid, alertId);

        assertThat(matches).hasSize(1);
        AlertTripMatchDto dto = matches.get(0);
        assertThat(dto.departureDate()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(dto.travelerName()).isEqualTo(MatchingTextUtil.buildName(travelerEntity));
        assertThat(dto.travelerInitials()).isEqualTo(MatchingTextUtil.buildInitials(travelerEntity));
        assertThat(dto.availableKg()).isEqualTo(new BigDecimal("15.00"));
        assertThat(dto.pricePerKg()).isEqualTo(new BigDecimal("8.50"));
        assertThat(dto.transportMode()).isEqualTo(TransportMode.PLANE);
        assertThat(dto.travelerRating()).isCloseTo(4.7, within(0.0001));
    }

    @Test
    void getTripMatches_ratingNull_defaultsToZero() {
        UUID travelerId = UUID.randomUUID();
        AnnouncementEntity inWindow = trip(travelerId, LocalDate.of(2026, 7, 15),
                new BigDecimal("5.00"), new BigDecimal("10.00"));
        UserEntity travelerEntity = traveler(travelerId, "Awa", "Keita", null);

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(senderAlert()));
        when(announcementRepository.findActiveByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(inWindow));
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(travelerEntity));

        List<AlertTripMatchDto> matches = service.getTripMatches(uid, alertId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).travelerRating()).isEqualTo(0.0);
    }

    @Test
    void getTripMatches_notOwner_throwsNotFound() {
        CorridorAlertEntity foreign = senderAlert();
        foreign.setOwnerId(UUID.randomUUID());
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getTripMatches(uid, alertId))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void countMatches_travelerDirection_doesNotTouchAnnouncementRepository() {
        CorridorAlertEntity travelerAlert = new CorridorAlertEntity();
        setId(travelerAlert, alertId);
        travelerAlert.setOwnerId(ownerId);
        travelerAlert.setDepartureCity("Paris");
        travelerAlert.setArrivalCity("Bamako");
        travelerAlert.setContentCategories(List.of());
        travelerAlert.setActive(true);
        travelerAlert.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);

        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(travelerAlert));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako")).thenReturn(List.of());

        List<com.dony.api.alerts.dto.CorridorAlertResponse> result = service.list(uid);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).matchCount()).isEqualTo(0L);
        verifyNoInteractions(announcementRepository);
    }

    @Test
    void countMatches_senderDirection_usesAnnouncementRepository() {
        UUID travelerId = UUID.randomUUID();
        AnnouncementEntity inWindow = trip(travelerId, LocalDate.of(2026, 7, 10),
                new BigDecimal("15.00"), new BigDecimal("8.50"));

        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(senderAlert()));
        when(announcementRepository.findActiveByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(inWindow));

        List<com.dony.api.alerts.dto.CorridorAlertResponse> list = service.list(uid);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).matchCount()).isEqualTo(1L);
        verify(announcementRepository).findActiveByCorridor("Paris", "Bamako");
        verifyNoInteractions(packageRequestRepository);
    }
}
