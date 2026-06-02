package com.dony.api.admin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Réglage admin du taux de commission d'un utilisateur.
 * {@code rate} null = retour au taux global ({@code dony.commission.rate}).
 * Sinon dans [0, 1[ (ex. 0.08 = 8 %).
 */
public record CommissionRateOverrideRequest(
        @DecimalMin(value = "0.0", message = "Le taux ne peut pas être négatif")
        @DecimalMax(value = "0.999", message = "Le taux doit être inférieur à 1")
        BigDecimal rate
) {}
