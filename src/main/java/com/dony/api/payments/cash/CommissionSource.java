package com.dony.api.payments.cash;

/**
 * Indique comment prélever la commission Dony à l'acceptation d'un bid hors escrow.
 * <ul>
 *   <li>{@link #WALLET_FIRST} (défaut) : débit du wallet si solde suffisant, sinon
 *       on retourne INSUFFICIENT_WALLET pour laisser le voyageur choisir (recharger / carte).</li>
 *   <li>{@link #CARD} : forcer le prélèvement sur la carte de commission (fallback explicite
 *       choisi par le voyageur après un solde wallet insuffisant).</li>
 * </ul>
 */
public enum CommissionSource {
    WALLET_FIRST,
    CARD
}
