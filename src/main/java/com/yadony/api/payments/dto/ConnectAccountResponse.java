package com.yadony.api.payments.dto;

import com.yadony.api.auth.StripeAccountStatus;

public record ConnectAccountResponse(
        String stripeAccountId,
        StripeAccountStatus stripeAccountStatus
) {}
