package com.dony.api.matching.dto;

import java.math.BigDecimal;

/**
 * Résumé d'activité d'un utilisateur sur une période.
 *
 * <p>{@code kgSoldThisMonth} et {@code revenueThisMonth} sont conservés pour les
 * clients déployés qui les lisent encore : ils portent désormais les mêmes
 * valeurs que {@code kgSold} et {@code revenue}. Les nouveaux champs sont bornés
 * à la période demandée, indiquée par {@code period}.
 */
public record TripsSummaryDto(
        long activeTrips,
        BigDecimal kgSoldThisMonth,
        BigDecimal revenueThisMonth,
        BigDecimal kgSold,
        BigDecimal revenue,
        long tripsPublished,
        long parcelsSent,
        String period
) {
    /** Construit un résumé en dupliquant les valeurs sur les champs historiques. */
    public static TripsSummaryDto of(
            long activeTrips,
            BigDecimal kgSold,
            BigDecimal revenue,
            long tripsPublished,
            long parcelsSent,
            String period) {
        return new TripsSummaryDto(
                activeTrips,
                kgSold,
                revenue,
                kgSold,
                revenue,
                tripsPublished,
                parcelsSent,
                period);
    }
}
