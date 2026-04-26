package com.dony.api.tracking.dto;

import java.util.UUID;

public record QrCodeResponse(
        UUID bidId,
        String scanUrl,
        String qrCodeBase64
) {}
