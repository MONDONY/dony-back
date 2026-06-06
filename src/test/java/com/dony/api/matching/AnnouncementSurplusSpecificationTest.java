package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Surplus capacity — Task A8.
 * Vérifie que {@link AnnouncementSpecification#publicOrOpenSurplus()} :
 *  - inclut les trajets publics (linkedPackageRequestId == null) ;
 *  - inclut les trajets dédiés avec surplus ouvert (surplusPublished && availableKg > 0) ;
 *  - exclut les trajets dédiés sans surplus ouvert.
 */
@DataJpaTest
@ActiveProfiles("test")
class AnnouncementSurplusSpecificationTest {

    @Autowired
    private AnnouncementRepository repository;

    private AnnouncementEntity persist(UUID linkedRequestId, boolean surplusPublished,
                                       BigDecimal availableKg, BigDecimal reservedKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG Terminal 2E");
        a.setPickupLat(new BigDecimal("49.009000"));
        a.setPickupLng(new BigDecimal("2.547000"));
        a.setDeliveryAddressLabel("Aéroport LSS");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setAvailableKg(availableKg);
        a.setTotalKg(availableKg.add(reservedKg));
        a.setReservedKg(reservedKg);
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setLinkedPackageRequestId(linkedRequestId);
        a.setSurplusPublished(surplusPublished);
        return repository.saveAndFlush(a);
    }

    @Test
    void publicOrOpenSurplus_includesPublicTrip() {
        AnnouncementEntity publicTrip = persist(null, false, new BigDecimal("20"), BigDecimal.ZERO);

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.publicOrOpenSurplus();
        List<AnnouncementEntity> results = repository.findAll(spec);

        assertThat(results).extracting(AnnouncementEntity::getId).contains(publicTrip.getId());
    }

    @Test
    void publicOrOpenSurplus_includesDedicatedWithOpenSurplus() {
        AnnouncementEntity dedicatedOpen =
            persist(UUID.randomUUID(), true, new BigDecimal("8"), new BigDecimal("5"));

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.publicOrOpenSurplus();
        List<AnnouncementEntity> results = repository.findAll(spec);

        assertThat(results).extracting(AnnouncementEntity::getId).contains(dedicatedOpen.getId());
    }

    @Test
    void publicOrOpenSurplus_excludesDedicatedWithoutOpenSurplus() {
        AnnouncementEntity dedicatedClosed =
            persist(UUID.randomUUID(), false, new BigDecimal("5"), new BigDecimal("5"));

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.publicOrOpenSurplus();
        List<AnnouncementEntity> results = repository.findAll(spec);

        assertThat(results).extracting(AnnouncementEntity::getId).doesNotContain(dedicatedClosed.getId());
    }

    @Test
    void publicOrOpenSurplus_excludesDedicatedSurplusPublishedButZeroAvailable() {
        // Edge: surplus published but availableKg == 0 (fully booked) → excluded.
        AnnouncementEntity fullyBooked =
            persist(UUID.randomUUID(), true, BigDecimal.ZERO, new BigDecimal("5"));

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.publicOrOpenSurplus();
        List<AnnouncementEntity> results = repository.findAll(spec);

        assertThat(results).extracting(AnnouncementEntity::getId).doesNotContain(fullyBooked.getId());
    }
}
