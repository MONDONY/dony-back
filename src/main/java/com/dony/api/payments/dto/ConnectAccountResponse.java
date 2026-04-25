package com.dony.api.payments.dto;

public record ConnectAccountResponse(
        String stripeAccountId,
        boolean stripeOnboarded
) {}
