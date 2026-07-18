package com.dony.api.common.money;

import java.util.Map;
import java.util.Optional;

/**
 * Pays zone CFA → devise. Utilisé comme repli quand le PSP n'a pas encore
 * fourni la devise du wallet (règle R4 : la devise de débit vient du wallet ;
 * ce mapping couvre le stub actuel où seul le countryCode du bid est connu).
 */
public final class CountryCurrencies {

    private static final Map<String, String> CFA = Map.ofEntries(
            // UEMOA — XOF
            Map.entry("SN", "XOF"), Map.entry("CI", "XOF"), Map.entry("ML", "XOF"),
            Map.entry("BF", "XOF"), Map.entry("BJ", "XOF"), Map.entry("TG", "XOF"),
            Map.entry("NE", "XOF"), Map.entry("GW", "XOF"),
            // CEMAC — XAF
            Map.entry("CM", "XAF"), Map.entry("GA", "XAF"), Map.entry("TD", "XAF"),
            Map.entry("CG", "XAF"), Map.entry("CF", "XAF"), Map.entry("GQ", "XAF"));

    private CountryCurrencies() {}

    public static Optional<String> forCountry(String iso2) {
        return Optional.ofNullable(iso2 == null ? null : CFA.get(iso2.toUpperCase()));
    }
}
