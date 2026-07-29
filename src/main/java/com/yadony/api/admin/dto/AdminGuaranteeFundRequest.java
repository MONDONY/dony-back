package com.yadony.api.admin.dto;

import java.util.UUID;

public record AdminGuaranteeFundRequest(
        int amountCents,
        UUID beneficiaryUserId,
        String reason
) {}
