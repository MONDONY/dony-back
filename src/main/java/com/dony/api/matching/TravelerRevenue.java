package com.dony.api.matching;

import java.math.BigDecimal;

/**
 * Revenu voyageur = carte (escrow Stripe libéré) + espèces (net des bids CASH
 * livrés, qui ne créent aucun PaymentEntity).
 *
 * <p>Centralise la règle « carte + cash » et le repli à zéro pour que les trois
 * vues de statistiques (Activités, profil, analytics pro) ne puissent pas
 * diverger — et surtout qu'un futur consommateur n'oublie pas le terme espèces,
 * le bug même que cette feature corrige.
 */
final class TravelerRevenue {

    private TravelerRevenue() {}

    /** Somme carte + cash, chaque terme replié à zéro s'il est null. */
    static BigDecimal cardPlusCash(BigDecimal card, BigDecimal cash) {
        return orZero(card).add(orZero(cash));
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
