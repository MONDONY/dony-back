package com.dony.api.matching.dto;

import java.math.BigDecimal;

/**
 * Résultat du devis — permet à l'app d'afficher le total exact (promo inclus)
 * sans calculer localement.
 */
public record BidQuoteResponse(
        /** Montant net voyageur (= weightKg × pricePerKg). */
        BigDecimal netEur,
        /** Taux de commission Dony effectif (promo/override/global). */
        BigDecimal rate,
        /** Commission Dony = netEur × rate. */
        BigDecimal commissionEur,
        /** Total expéditeur = netEur + commissionEur. */
        BigDecimal totalEur,
        /** true si un code promo a été appliqué. */
        boolean promoApplied,
        /** Ex. « Code WELCOME10 : −6 % » (null si pas de promo). */
        String promoLabel
) {}
