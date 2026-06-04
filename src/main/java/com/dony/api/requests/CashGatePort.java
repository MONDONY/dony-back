package com.dony.api.requests;

import java.math.BigDecimal;
import java.util.UUID;

public interface CashGatePort {
    /** Returns true if the traveler has sufficient funds to cover the commission amount. */
    boolean hasSufficientFunds(UUID travelerId, BigDecimal commissionAmount);

    /**
     * Charges dony's commission (netAmount × rate) from the traveler for a CASH
     * negotiated thread, wallet-first then card. Returns true if successfully
     * charged (or already charged — idempotent), false if it could not be charged.
     * Implementations MUST NOT throw on a normal decline — return false instead.
     */
    boolean chargeNegotiationCashCommission(java.util.UUID travelerId, java.util.UUID senderId, java.util.UUID threadId, java.math.BigDecimal netAmount);
}
