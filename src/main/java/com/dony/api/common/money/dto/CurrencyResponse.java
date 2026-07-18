package com.dony.api.common.money.dto;

import com.dony.api.common.money.CurrencyEntity;

import java.math.BigDecimal;

public record CurrencyResponse(String code, int minorUnit, String symbol,
                               BigDecimal pegRateToEur, int roundingIncrement) {
    public static CurrencyResponse from(CurrencyEntity e) {
        return new CurrencyResponse(e.getCode(), e.getMinorUnit(), e.getSymbol(),
                e.getPegRateToEur(), e.getRoundingIncrement());
    }
}
