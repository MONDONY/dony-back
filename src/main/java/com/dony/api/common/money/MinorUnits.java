package com.dony.api.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SEUL point de conversion montant ↔ unités mineures (règle R5 du spec devise).
 * Interdit ailleurs : multiply(100), divide(100), movePointLeft(2), valueOf(cents, 2).
 * EUR: exponent 2 · XOF/XAF: exponent 0 — un ×100 codé en dur débiterait 100× en F CFA.
 */
public final class MinorUnits {

    private MinorUnits() {}

    /** Arrondit HALF_UP. Pour montants indicatifs, DTOs, analytics. */
    public static long toMinor(Money money, CurrencyRegistry registry) {
        return money.amount()
                .movePointRight(registry.minorUnitOf(money.currencyCode()))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * Lève ArithmeticException si le montant a plus de décimales que la devise.
     * Pour tout chemin qui déplace de l'argent réel : préserve le fail-fast
     * des anciens sites multiply(100).longValueExact() (spec §5.2).
     */
    public static long toMinorExact(Money money, CurrencyRegistry registry) {
        return money.amount()
                .movePointRight(registry.minorUnitOf(money.currencyCode()))
                .longValueExact();   // lève si partie décimale résiduelle
    }

    /** Sens retour (webhooks, crédits wallet). Symétrique de toMinor. */
    public static Money fromMinor(long minor, String currencyCode, CurrencyRegistry registry) {
        BigDecimal amount = BigDecimal.valueOf(minor)
                .movePointLeft(registry.minorUnitOf(currencyCode));
        return new Money(amount, currencyCode);
    }
}
