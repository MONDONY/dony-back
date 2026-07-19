package com.dony.api.payments.wallet;

import java.util.Map;
import java.util.Set;

/**
 * Couverture réelle GeniusPay par pays/rail (doc API lue le 2026-07-19).
 * Distincte de CountryCurrencies (qui ne résout que la devise) — un pays
 * peut être en zone XOF sans que GeniusPay y couvre tous les rails
 * (ex. MTN Money : CI/BF seulement, pas SN/ML).
 */
public final class GeniusPayCoverage {

    private static final Map<String, Set<String>> COVERAGE = Map.of(
            "SN", Set.of("WAVE", "ORANGE_MONEY"),
            "CI", Set.of("WAVE", "ORANGE_MONEY", "MTN_MONEY"),
            "ML", Set.of("WAVE", "ORANGE_MONEY"),
            "BF", Set.of("WAVE", "ORANGE_MONEY", "MTN_MONEY")
    );

    private GeniusPayCoverage() {}

    public static boolean supports(String countryCode, String provider) {
        if (countryCode == null || provider == null) return false;
        Set<String> rails = COVERAGE.get(countryCode.toUpperCase());
        return rails != null && rails.contains(provider);
    }
}
