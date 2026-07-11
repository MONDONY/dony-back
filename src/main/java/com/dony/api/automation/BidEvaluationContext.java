package com.dony.api.automation;

import java.math.BigDecimal;

/**
 * Valeurs résolues une fois par bid par AutomationBidListener, consommées par
 * CustomRuleConditionEvaluator. Tout champ peut être null (donnée absente) —
 * une condition portant sur un champ null n'est jamais satisfaite (fail-safe).
 */
record BidEvaluationContext(
        BigDecimal weightKg,
        String corridor,
        String contentCategory,
        BigDecimal senderRating,
        BigDecimal capacityFreeKg,
        Long hoursBeforeDeparture) {
}
