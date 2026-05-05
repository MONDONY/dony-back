package com.dony.api.payments.dto;

import com.dony.api.auth.StripeAccountStatus;

public record ConnectAccountResponse(
        String stripeAccountId,
        StripeAccountStatus stripeAccountStatus
) {}
