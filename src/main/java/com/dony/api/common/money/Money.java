package com.dony.api.common.money;

import java.math.BigDecimal;
import java.util.Objects;

/** Montant + devise ISO 4217. La devise contractuelle dony est toujours EUR (spec §3.3). */
public record Money(BigDecimal amount, String currencyCode) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currencyCode, "currencyCode");
    }
}
