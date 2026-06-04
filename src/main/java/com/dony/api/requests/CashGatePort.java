package com.dony.api.requests;

import java.math.BigDecimal;
import java.util.UUID;

public interface CashGatePort {
    /** Returns true if the traveler has sufficient funds to cover the commission amount. */
    boolean hasSufficientFunds(UUID travelerId, BigDecimal commissionAmount);
}
