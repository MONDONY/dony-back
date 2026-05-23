package com.dony.api.matching;

import com.dony.api.auth.UserBlockEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confidentialité v2 — Task 8.
 * Vérifie que {@link AnnouncementSpecification#notBlockedBy(UUID)} exclut, dans les deux sens,
 * les annonces dont le voyageur est en relation de blocage avec le viewer.
 */
@DataJpaTest
@ActiveProfiles("test")
class AnnouncementSpecificationBlockTest {

    @Autowired
    private AnnouncementRepository repository;

    @Autowired
    private TestEntityManager em;

    private AnnouncementEntity persistActiveAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
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
        a.setAvailableKg(new BigDecimal("20"));
        a.setTotalKg(new BigDecimal("20"));
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return repository.saveAndFlush(a);
    }

    private void persistBlock(UUID blockerId, UUID blockedId) {
        UserBlockEntity b = new UserBlockEntity();
        b.setBlockerId(blockerId);
        b.setBlockedId(blockedId);
        em.persistAndFlush(b);
    }

    @Test
    void noBlock_announcementIsPresent() {
        UUID viewer = UUID.randomUUID();
        UUID traveler = UUID.randomUUID();
        AnnouncementEntity ann = persistActiveAnnouncement(traveler);

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.notBlockedBy(viewer);
        List<AnnouncementEntity> results = repository.findAll(spec);

        assertThat(results).extracting(AnnouncementEntity::getId).contains(ann.getId());
    }

    @Test
    void viewerBlockedTraveler_announcementIsAbsent() {
        UUID viewer = UUID.randomUUID();
        UUID traveler = UUID.randomUUID();
        AnnouncementEntity ann = persistActiveAnnouncement(traveler);
        // viewer (blocker) → traveler (blocked)
        persistBlock(viewer, traveler);

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.notBlockedBy(viewer);
        List<AnnouncementEntity> results = repository.findAll(spec);

        assertThat(results).extracting(AnnouncementEntity::getId).doesNotContain(ann.getId());
    }

    @Test
    void travelerBlockedViewer_announcementIsAbsent() {
        UUID viewer = UUID.randomUUID();
        UUID traveler = UUID.randomUUID();
        AnnouncementEntity ann = persistActiveAnnouncement(traveler);
        // traveler (blocker) → viewer (blocked) : sens inverse
        persistBlock(traveler, viewer);

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.notBlockedBy(viewer);
        List<AnnouncementEntity> results = repository.findAll(spec);

        assertThat(results).extracting(AnnouncementEntity::getId).doesNotContain(ann.getId());
    }

    @Test
    void nullViewer_noFilterApplied_announcementIsPresent() {
        UUID traveler = UUID.randomUUID();
        AnnouncementEntity ann = persistActiveAnnouncement(traveler);

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.notBlockedBy(null);
        List<AnnouncementEntity> results = repository.findAll(spec);

        assertThat(results).extracting(AnnouncementEntity::getId).contains(ann.getId());
    }
}
