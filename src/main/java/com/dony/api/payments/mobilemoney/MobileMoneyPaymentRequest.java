package com.dony.api.payments.mobilemoney;

import java.math.BigDecimal;
import java.util.UUID;

public record MobileMoneyPaymentRequest(
    UUID bidId,
    String phoneNumber,
    String countryCode,
    BigDecimal amount,
    String currency
) {}
