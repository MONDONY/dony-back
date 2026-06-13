package com.dony.api.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Couvre {@link AnnouncementService#deriveDepartureAt} (D1) : fusion date+heure
 * dans le fuseau de la ville de départ, défaut Europe/Paris, garde sur l'absence.
 */
class DepartureAtDerivationTest {

    @Test
    void fuses_date_and_time_in_paris_summer_offset() {
        OffsetDateTime result = AnnouncementService.deriveDepartureAt(
                LocalDate.of(2026, 7, 1), LocalTime.of(10, 0), "Europe/Paris");
        assertThat(result).isEqualTo(OffsetDateTime.parse("2026-07-01T10:00:00+02:00"));
    }

    @Test
    void fuses_date_and_time_in_paris_winter_offset() {
        OffsetDateTime result = AnnouncementService.deriveDepartureAt(
                LocalDate.of(2026, 1, 15), LocalTime.of(8, 30), "Europe/Paris");
        assertThat(result).isEqualTo(OffsetDateTime.parse("2026-01-15T08:30:00+01:00"));
    }

    @Test
    void null_zone_falls_back_to_paris() {
        OffsetDateTime result = AnnouncementService.deriveDepartureAt(
                LocalDate.of(2026, 7, 1), LocalTime.of(10, 0), null);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(2));
    }

    @Test
    void blank_zone_falls_back_to_paris() {
        OffsetDateTime result = AnnouncementService.deriveDepartureAt(
                LocalDate.of(2026, 7, 1), LocalTime.of(10, 0), "   ");
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(2));
    }

    @Test
    void null_time_returns_null() {
        assertThat(AnnouncementService.deriveDepartureAt(
                LocalDate.of(2026, 7, 1), null, "Europe/Paris")).isNull();
    }

    @Test
    void null_date_returns_null() {
        assertThat(AnnouncementService.deriveDepartureAt(
                null, LocalTime.of(10, 0), "Europe/Paris")).isNull();
    }
}
