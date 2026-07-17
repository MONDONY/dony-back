package com.dony.api.requests.specification;

import com.dony.api.requests.entity.ParcelSize;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-level test (Task 3, follow-up): proves {@link PackageRequestSpecifications#urgent(int)}
 * actually excludes/includes rows once evaluated as SQL by JPA/Hibernate against a real
 * (H2, test profile) database — the predicate-construction test in
 * {@link PackageRequestSpecificationsTest} only proves the {@code CriteriaBuilder} calls are
 * correct with a mocked {@code CriteriaBuilder}, not that the resulting {@code BETWEEN} clause
 * filters real rows correctly at the boundaries. Mirrors the existing DB-level Specification test
 * pattern used for {@code AnnouncementSpecification.publicOrOpenSurplus()} in
 * {@code AnnouncementSurplusSpecificationTest}.
 */
@DataJpaTest
@ActiveProfiles("test")
class PackageRequestUrgentSpecificationDbTest {

    @Autowired
    private PackageRequestRepository repository;

    private static final int THRESHOLD_DAYS = 3;

    private PackageRequestEntity persist(LocalDate desiredDate) {
        PackageRequestEntity e = new PackageRequestEntity();
        e.setSenderId(UUID.randomUUID());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(desiredDate);
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("5"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(com.dony.api.matching.TransportMode.PLANE);
        e.setContentCategory("vetements");
        e.setStatus(PackageRequestStatus.OPEN);
        return repository.saveAndFlush(e);
    }

    @Test
    @DisplayName("urgent(3) exécuté en SQL réel : inclut [today, today+3], exclut today+4 et le passé")
    void urgent_evaluatedAgainstRealDb_includesOnlyRequestsWithinThresholdInclusive() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        PackageRequestEntity withinLow = persist(today.plusDays(2));   // dans la fenêtre
        PackageRequestEntity onBoundary = persist(today.plusDays(3));  // borne haute incluse
        PackageRequestEntity justBeyond = persist(today.plusDays(4));  // exclu (juste après la borne)
        PackageRequestEntity past = persist(today.minusDays(1));       // exclu (passé)

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.urgent(THRESHOLD_DAYS);
        List<PackageRequestEntity> results = repository.findAll(spec);

        assertThat(results)
            .extracting(PackageRequestEntity::getId)
            .contains(withinLow.getId(), onBoundary.getId())
            .doesNotContain(justBeyond.getId(), past.getId());
    }

    @Test
    @DisplayName("urgent(3) exécuté en SQL réel : exclut aussi today (borne basse incluse) si on la teste explicitement")
    void urgent_evaluatedAgainstRealDb_includesTodayItself() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        PackageRequestEntity isToday = persist(today);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.urgent(THRESHOLD_DAYS);
        List<PackageRequestEntity> results = repository.findAll(spec);

        assertThat(results).extracting(PackageRequestEntity::getId).contains(isToday.getId());
    }
}
