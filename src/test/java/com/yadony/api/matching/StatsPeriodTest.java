package com.yadony.api.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatsPeriodTest {

    @Test
    void every_period_has_a_distinct_api_value() {
        assertThat(StatsPeriod.LAST_7_DAYS.apiValue()).isEqualTo("7d");
        assertThat(StatsPeriod.LAST_30_DAYS.apiValue()).isEqualTo("30d");
        assertThat(StatsPeriod.LAST_12_MONTHS.apiValue()).isEqualTo("12m");
    }

    @Test
    void fromApiValue_resolves_known_values() {
        for (StatsPeriod period : StatsPeriod.values()) {
            assertThat(StatsPeriod.fromApiValue(period.apiValue())).isEqualTo(period);
        }
    }

    @Test
    void fromApiValue_falls_back_to_the_default_rather_than_failing() {
        // Un client plus récent qui enverrait une période inconnue doit
        // recevoir des chiffres, pas une erreur.
        assertThat(StatsPeriod.fromApiValue("bogus")).isEqualTo(StatsPeriod.DEFAULT);
        assertThat(StatsPeriod.fromApiValue(null)).isEqualTo(StatsPeriod.DEFAULT);
        assertThat(StatsPeriod.fromApiValue("")).isEqualTo(StatsPeriod.DEFAULT);
    }

    @Test
    void start_matches_the_declared_window() {
        assertThat(StatsPeriod.LAST_7_DAYS.start().toLocalDate())
                .isEqualTo(LocalDate.now().minusDays(7));
        assertThat(StatsPeriod.LAST_30_DAYS.start().toLocalDate())
                .isEqualTo(LocalDate.now().minusDays(30));
        assertThat(StatsPeriod.LAST_12_MONTHS.start().toLocalDate())
                .isEqualTo(LocalDate.now().minusMonths(12));
    }

    @Test
    void cacheKey_is_unique_per_period() {
        UUID userId = UUID.randomUUID();

        assertThat(StatsPeriod.values())
                .extracting(p -> StatsPeriod.cacheKey(userId, p))
                .doesNotHaveDuplicates()
                .allMatch(key -> key.startsWith(userId.toString()));
    }
}
