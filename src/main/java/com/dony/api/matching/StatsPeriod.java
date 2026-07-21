package com.dony.api.matching;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * Intervalle des statistiques d'activité.
 *
 * <p>Enum plutôt que chaînes libres : le calcul de la borne et la construction
 * de la clé de cache sont exhaustifs par construction. Ajouter une période ne
 * peut donc pas laisser derrière elle une fenêtre par défaut silencieuse ou une
 * entrée de cache jamais évincée.
 */
public enum StatsPeriod {
    LAST_7_DAYS("7d") {
        @Override
        LocalDate startDate(LocalDate today) {
            return today.minusDays(7);
        }
    },
    LAST_30_DAYS("30d") {
        @Override
        LocalDate startDate(LocalDate today) {
            return today.minusDays(30);
        }
    },
    LAST_12_MONTHS("12m") {
        @Override
        LocalDate startDate(LocalDate today) {
            return today.minusMonths(12);
        }
    };

    public static final StatsPeriod DEFAULT = LAST_30_DAYS;

    private final String apiValue;

    StatsPeriod(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    abstract LocalDate startDate(LocalDate today);

    /** Début de la fenêtre, au premier instant du jour. */
    public LocalDateTime start() {
        return startDate(LocalDate.now()).atStartOfDay();
    }

    /**
     * Une valeur inconnue retombe sur {@link #DEFAULT} : un client plus récent
     * qui enverrait une nouvelle période ne doit pas recevoir d'erreur.
     */
    public static StatsPeriod fromApiValue(String value) {
        return Arrays.stream(values())
                .filter(p -> p.apiValue.equals(value))
                .findFirst()
                .orElse(DEFAULT);
    }

    /** Clé de cache d'un résumé — définie ici pour rester unique. */
    public static String cacheKey(Object userId, StatsPeriod period) {
        return userId + "-" + period.apiValue;
    }
}
