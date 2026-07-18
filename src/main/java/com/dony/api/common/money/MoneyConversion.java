package com.dony.api.common.money;

import java.math.BigDecimal;

public record MoneyConversion(Money source, Money target, BigDecimal rate, String rateSource) {}
