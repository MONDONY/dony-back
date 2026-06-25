package com.dony.api.admin.dto;

import java.util.UUID;

public record AdminGuaranteeFundRequest(
        int amountCents,
        UUID beneficiaryUserId,
        String reason
) {}
