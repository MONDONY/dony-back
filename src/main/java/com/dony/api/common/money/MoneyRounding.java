package com.dony.api.common.money;

/**
 * Arrondis en unités mineures (spec §5.6). Deux contextes :
 * transactionnel (débit réel, incrément local ex. 5 F CFA, plancher) et
 * remboursement (incrément supérieur, faveur utilisateur). L'arrondi
 * INDICATIF (affichage) n'utilise pas cette classe : MinorUnits.toMinor suffit.
 */
public final class MoneyRounding {

    private MoneyRounding() {}

    /** Plus proche multiple de l'incrément ; jamais 0 pour un dû strictement positif. */
    public static long roundTransactionalMinor(long minor, int increment) {
        if (increment <= 1) return minor;
        long rounded = Math.round((double) minor / increment) * increment;
        if (rounded == 0 && minor > 0) return increment;   // plancher
        return rounded;
    }

    /** Incrément SUPÉRIEUR (remboursements — en faveur de l'utilisateur). */
    public static long roundRefundMinor(long minor, int increment) {
        if (increment <= 1) return minor;
        return ((minor + increment - 1) / increment) * increment;
    }
}
