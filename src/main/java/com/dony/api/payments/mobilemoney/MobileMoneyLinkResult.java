package com.dony.api.payments.mobilemoney;

import java.time.LocalDateTime;

public record MobileMoneyLinkResult(
    String externalReference,
    String paymentLink,
    LocalDateTime expiresAt
) {}
