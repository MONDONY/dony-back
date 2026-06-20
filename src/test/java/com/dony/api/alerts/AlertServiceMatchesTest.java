package com.dony.api.alerts;

import com.dony.api.alerts.dto.CorridorAlertRequest;
import com.dony.api.alerts.dto.CorridorAlertResponse;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.dto.MatchingRequestDto;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceMatchesTest {

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

    private CorridorAlertEntity alert(boolean active) {
        CorridorAlertEntity a = new CorridorAlertEntity();
        setId(a, alertId);
        a.setOwnerId(ownerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDateFrom(LocalDate.of(2026, 7, 1));
        a.setDateTo(LocalDate.of(2026, 7, 31));
        a.setMinWeightKg(new BigDecimal("2.00"));
        a.setContentCategories(List.of("Documents"));
        a.setActive(active);
        return a;
    }

    private PackageRequestEntity pkg(String content, BigDecimal weight, LocalDate date) {
        PackageRequestEntity p = new PackageRequestEntity();
        setId(p, UUID.randomUUID());
        p.setSenderId(UUID.randomUUID());
        p.setDepartureCity("Paris");
        p.setArrivalCity("Bamako");
        p.setContentCategory(content);
        p.setWeightKg(weight);
        p.setDesiredDate(date);
        p.setDateToleranceDays((short) 2);
        p.setStatus(PackageRequestStatus.OPEN);
        return p;
    }

    /** Creates a UserEntity with the given ID set via reflection. */
    private UserEntity senderWithId(UUID id) {
        UserEntity u = new UserEntity();
        setId(u, id);
        return u;
    }

    @Test
    void getMatches_filtersByWeightDateAndCategory() {
        PackageRequestEntity match = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        PackageRequestEntity tooLight = pkg("Documents", new BigDecimal("1.00"), LocalDate.of(2026, 7, 10));
        PackageRequestEntity outOfWindow = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 8, 15));
        PackageRequestEntity wrongCategory = pkg("Vêtements", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert(true)));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(match, tooLight, outOfWindow, wrongCategory));
        // Item 5: service now calls findAllById — return a sender for the one matching package
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(senderWithId(match.getSenderId())));

        List<MatchingRequestDto> matches = service.getMatches(uid, alertId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).contentType()).isEqualTo("Documents");
        assertThat(matches.get(0).weightKg()).isEqualTo(3.0);
    }

    @Test
    void getMatches_noFilters_returnsAllOpen() {
        PackageRequestEntity p = pkg("Anything", new BigDecimal("0.50"), LocalDate.of(2030, 1, 1));
        CorridorAlertEntity a = alert(true);
        a.setDateFrom(null);
        a.setDateTo(null);
        a.setMinWeightKg(null);
        a.setContentCategories(List.of());
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(a));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(p));
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(senderWithId(p.getSenderId())));

        assertThat(service.getMatches(uid, alertId)).hasSize(1);
    }

    @Test
    void getMatches_notOwner_throwsNotFound() {
        CorridorAlertEntity foreign = alert(true);
        foreign.setOwnerId(UUID.randomUUID());
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getMatches(uid, alertId))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void list_returnsItemsWithMatchCount() {
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(alert(true)));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10))));

        List<CorridorAlertResponse> list = service.list(uid);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).matchCount()).isEqualTo(1L);
    }

    @Test
    void update_togglesActiveAndFilters() {
        CorridorAlertEntity existing = alert(true);
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(existing));
        when(alertRepository.save(existing)).thenReturn(existing);
        when(packageRequestRepository.findOpenByCorridor(any(), any())).thenReturn(List.of());

        CorridorAlertRequest req = new CorridorAlertRequest("Lyon", "FR", "Dakar", "SN",
                null, null, null, List.of(), null);
        CorridorAlertResponse resp = service.update(uid, alertId, req, false);

        assertThat(existing.getDepartureCity()).isEqualTo("Lyon");
        assertThat(existing.getArrivalCity()).isEqualTo("Dakar");
        assertThat(existing.isActive()).isFalse();
        assertThat(resp.active()).isFalse();
    }

    @Test
    void update_notOwner_throwsNotFound() {
        CorridorAlertEntity foreign = alert(true);
        foreign.setOwnerId(UUID.randomUUID());
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.update(uid, alertId,
                new CorridorAlertRequest("A", null, "B", null, null, null, null, List.of(), null), null))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void delete_softDeletesOwnedAlert() {
        CorridorAlertEntity existing = alert(true);
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(existing));
        when(alertRepository.save(existing)).thenReturn(existing);

        service.delete(uid, alertId);

        assertThat(existing.getDeletedAt()).isNotNull();
        verify(alertRepository).save(existing);
    }
}
