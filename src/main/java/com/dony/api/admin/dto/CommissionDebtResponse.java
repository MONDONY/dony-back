package com.dony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Créance de commission (bid en {@code commissionStatus = FAILED}) — rendue
 * visible côté admin pour recouvrement manuel (spec §4.4). La stratégie de
 * recouvrement effective reste au spec PSP ; cet endpoint expose seulement
 * l'état, daté.
 */
public record CommissionDebtResponse(
        UUID bidId,
        UUID travelerId,
        BigDecimal amountOwedEur,
        int retryCount,
        LocalDateTime failedAt
) {}
