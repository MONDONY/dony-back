package com.dony.api.alerts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CorridorAlertRepositoryTest {

    @Autowired CorridorAlertRepository repository;

    private CorridorAlertEntity newAlert(UUID travelerId, boolean active) {
        CorridorAlertEntity a = new CorridorAlertEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDateFrom(LocalDate.of(2026, 7, 1));
        a.setDateTo(LocalDate.of(2026, 7, 31));
        a.setMinWeightKg(new BigDecimal("2.00"));
        a.setActive(active);
        a.setContentCategories(List.of("Vêtements", "Documents"));
        return a;
    }

    @Test
    void persistsAndReadsBackWithCollections() {
        UUID travelerId = UUID.randomUUID();
        CorridorAlertEntity saved = repository.saveAndFlush(newAlert(travelerId, true));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getContentCategories()).containsExactlyInAnyOrder("Vêtements", "Documents");
    }

    @Test
    void findAllByTravelerId_scopesToOwner() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        repository.saveAndFlush(newAlert(owner, true));
        repository.saveAndFlush(newAlert(other, true));

        List<CorridorAlertEntity> mine = repository.findAllByTravelerId(owner);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getTravelerId()).isEqualTo(owner);
    }

    @Test
    void countByTravelerId_countsRows() {
        UUID owner = UUID.randomUUID();
        repository.saveAndFlush(newAlert(owner, true));
        repository.saveAndFlush(newAlert(owner, false));

        assertThat(repository.countByTravelerId(owner)).isEqualTo(2L);
    }

    @Test
    void findAllByActiveTrue_excludesInactive() {
        UUID owner = UUID.randomUUID();
        repository.saveAndFlush(newAlert(owner, true));
        repository.saveAndFlush(newAlert(owner, false));

        assertThat(repository.findAllByActiveTrue()).hasSize(1);
    }

    @Test
    void softDelete_hiddenByWhereClause() {
        UUID owner = UUID.randomUUID();
        CorridorAlertEntity a = repository.saveAndFlush(newAlert(owner, true));
        a.softDelete();
        repository.saveAndFlush(a);

        assertThat(repository.findAllByTravelerId(owner)).isEmpty();
    }
}
